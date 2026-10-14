package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.plugin.postgresql.PostgreSQLImportReceiptDialect;
import ai.chat2db.plugin.postgresql.PostgreSQLPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Only the runner's newly owned loopback fixture is allowed; no default external database. */
@EnabledIfEnvironmentVariable(named="C2D_RECEIPT_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/c2d_receipt_it_[a-z0-9]+")
class ImportReceiptPostgreSQLIT {
    String url, password, schema, runKey;
    Connection keeper;
    ConnectInfo info;
    IPlugin previous;
    final PostgreSQLImportReceiptDialect dialect = new PostgreSQLImportReceiptDialect();
    final ImportReceiptStoreTest.Context context = new ImportReceiptStoreTest.Context();

    @BeforeEach void prepare() throws Exception {
        url=System.getenv("C2D_RECEIPT_JDBC_URL"); password=System.getenv("C2D_RECEIPT_PASSWORD");
        assertNotNull(password); schema="receipt_"+UUID.randomUUID().toString().replace("-", "");
        runKey=UUID.randomUUID().toString().replace("-", "")+"a".repeat(32);
        keeper=DriverManager.getConnection(url,"postgres",password);
        try(var s=keeper.createStatement()) {
            s.execute("CREATE SCHEMA \""+schema+"\"");
            s.execute("CREATE TABLE \""+schema+"\".\"TARGET_ROWS\" (ID INT, NOTE VARCHAR(100), CHECK (ID>0))");
        }
        info=new ConnectInfo();info.setDbType("POSTGRESQL");info.setSchemaName(schema);info.setDriverConfig(new DriverConfig());
        reconnect();previous=Chat2DBContext.PLUGIN_MAP.put("POSTGRESQL",new PostgreSQLPlugin());
        Chat2DBContext.putContext(info);
    }
    void reconnect() throws Exception { var c=DriverManager.getConnection(url,"postgres",password);c.setSchema(schema);info.setConnection(c); }
    ImportReceiptStore open() { return ImportReceiptStore.open(dialect,info,"TARGET_ROWS",runKey,"a".repeat(64),context); }
    @AfterEach void cleanup() throws Exception {
        Chat2DBContext.removeContext();
        if(previous==null)Chat2DBContext.PLUGIN_MAP.remove("POSTGRESQL");else Chat2DBContext.PLUGIN_MAP.put("POSTGRESQL",previous);
        if(info!=null&&info.getConnection()!=null) info.getConnection().close();
        if(keeper!=null) {try(var s=keeper.createStatement()){s.execute("DROP SCHEMA \""+schema+"\" CASCADE");}finally{keeper.close();}}
    }
    String insert(int id) {return "INSERT INTO \""+schema+"\".\"TARGET_ROWS\" VALUES ("+id+", 'row')";}
    long countTarget() throws Exception {try(var s=keeper.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM \""+schema+"\".\"TARGET_ROWS\"")){assertTrue(r.next());return r.getLong(1);}}

    @Test void reopeningNoKeyTableSkipsCommittedRowsAndRejectsChangedPayload() throws Exception {
        var store=open();assertTrue(store.execute(1,insert(1)));info.getConnection().close();reconnect();
        store=open();assertFalse(store.execute(1,insert(1)));assertTrue(store.execute(2,insert(2)));
        var current=store;assertThrows(ImportReceiptStore.ReceiptSafetyException.class,()->current.execute(1,insert(3)));
        assertThrows(ImportReceiptStore.ReceiptSafetyException.class,()->ImportReceiptStore.open(dialect,info,"TARGET_ROWS",runKey,"b".repeat(64),context));
        assertEquals(2,countTarget());assertTrue(info.getConnection().getAutoCommit());
    }
    @Test void unloggedAndPartitionedTargetsCannotAdvertiseReceiptRecovery() throws Exception {
        try(var s=keeper.createStatement()) {
            s.execute("CREATE UNLOGGED TABLE \""+schema+"\".\"UNLOGGED_ROWS\" (ID INT)");
            s.execute("CREATE TABLE \""+schema+"\".\"PARTITIONED_ROWS\" (ID INT) PARTITION BY RANGE (ID)");
        }
        assertFalse(dialect.supportsTransactionalTarget(info.getConnection(),info,"UNLOGGED_ROWS",context));
        assertFalse(dialect.supportsTransactionalTarget(info.getConnection(),info,"PARTITIONED_ROWS",context));
        assertTrue(dialect.supportsTransactionalTarget(info.getConnection(),info,"TARGET_ROWS",context));
    }
    @Test void unloggedReceiptWithMatchingColumnsIsRejectedBeforeHeaderOrTargetWrites() throws Exception {
        var location=dialect.location(info.getConnection(),info);
        try(var s=keeper.createStatement()) {s.execute(dialect.createTableSql(location).replace("CREATE TABLE", "CREATE UNLOGGED TABLE"));}
        assertThrows(ImportReceiptStore.ReceiptSafetyException.class,this::open);
        assertEquals(0,countTarget());
        try(var s=keeper.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+location.qualifiedName())) {assertTrue(r.next());assertEquals(0,r.getLong(1));}
    }
    @Test void lostCommitReplyAndRejectedRowsRemainReplaySafe() throws Exception {
        var store=open();Connection physical=info.getConnection();
        info.setConnection((Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{var result=method.invoke(physical,args);if(method.getName().equals("commit"))throw new SQLException("commit reply lost","08006");return result;}
            catch(InvocationTargetException error){throw error.getCause();}
        }));
        var unknown=assertThrows(ImportReceiptStore.ReceiptSafetyException.class,()->store.execute(1,insert(1)));
        assertEquals("IMPORT_COMMIT_OUTCOME_UNKNOWN",unknown.getCode());assertNull(info.getConnection());assertTrue(physical.isClosed());
        reconnect();var recovered=open();assertFalse(recovered.execute(1,insert(1)));assertEquals(1,countTarget());
        assertThrows(TaskExecutionException.class,()->recovered.execute(2,insert(-1)));
        recovered.recordRejected(2,insert(-1),"constraint rejection");info.getConnection().close();reconnect();
        var replay=open();var rejected=assertThrows(ImportReceiptStore.RejectedReceiptException.class,()->replay.execute(2,insert(-1)));
        assertEquals("constraint rejection",rejected.getMessage());assertEquals(1,countTarget());
        assertTrue(replay.execute(3,insert(3)));assertEquals(2,countTarget());
    }
}
