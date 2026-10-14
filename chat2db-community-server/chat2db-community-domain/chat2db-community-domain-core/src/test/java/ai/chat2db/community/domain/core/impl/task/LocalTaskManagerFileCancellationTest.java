package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.*;
import ai.chat2db.community.domain.api.service.task.*;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import ai.chat2db.community.storage.large.FileTaskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalTaskManagerFileCancellationTest {
    @TempDir Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void interruptedRunningTaskPersistsCancellationAndCleansResources(boolean interruptAfterCatch) throws Exception {
        var storage=new FileTaskStorage(directory.toString());
        var entered=new CountDownLatch(1); var blocked=new CountDownLatch(1); var cleanups=new AtomicInteger();
        var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
        TaskExecutor<ExportTaskSpec> executor=new TaskExecutor<>() {
            public String taskType(){return "QUERY_RESULT_EXPORT";}
            public Class<ExportTaskSpec> specType(){return ExportTaskSpec.class;}
            public void execute(ExportTaskSpec spec,TaskExecutionContext context) {
                worker.set(Thread.currentThread());
                entered.countDown();
                if (interruptAfterCatch) {
                    while (true) {
                        context.checkCancelled();
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                }
                try {blocked.await(5,TimeUnit.SECONDS);}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
                context.checkCancelled();
            }
            public void cleanupTerminalResources(ExportTaskSpec spec,Long id){cleanups.incrementAndGet();}
        };
        var manager=new LocalTaskManager(storage,new TaskExecutorRegistry(List.of(executor)),new ArtifactServiceImpl(),
                new ConnectionContextConverter(),new TaskExtensionManager(List.of(),List.of()),1,4);
        Task task=Task.builder().type("QUERY_RESULT_EXPORT").name("File cancellation fixture").build();
        try {
            manager.submit(task,TaskEvent.builder().code("TASK_CREATED").level("INFO").build(),
                    ExportTaskSpec.builder().taskType("QUERY_RESULT_EXPORT").format("CSV").build(),null,null);
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals("RUNNING",storage.get(task.getId()).orElseThrow().getStatus());
            if (interruptAfterCatch) {
                var field=LocalTaskManager.class.getDeclaredField("runningTaskRegistry");field.setAccessible(true);
                var running=((RunningTaskRegistry)field.get(manager)).get(task.getId());
                running.setFuture(new java.util.concurrent.Future<Void>() {
                    public boolean cancel(boolean mayInterrupt) {
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        while(System.nanoTime()<deadline&&!running.completionLock().hasQueuedThread(worker.get()))Thread.yield();
                        assertTrue(running.completionLock().hasQueuedThread(worker.get()),"worker must enter catch before interrupt arrives");
                        worker.get().interrupt();return true;
                    }
                    public boolean isCancelled(){return true;}
                    public boolean isDone(){return false;}
                    public Void get(){throw new UnsupportedOperationException();}
                    public Void get(long timeout,TimeUnit unit){throw new UnsupportedOperationException();}
                });
            }
            assertTrue(manager.cancel(task.getId()).isCancellationRequested());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline&&!("CANCELLED".equals(storage.get(task.getId()).orElseThrow().getStatus())&&cleanups.get()==1))Thread.sleep(10);
            assertEquals("CANCELLED",storage.get(task.getId()).orElseThrow().getStatus());
            assertEquals(1,cleanups.get());
            assertEquals("CANCELLED",new FileTaskStorage(directory.toString()).get(task.getId()).orElseThrow().getStatus());
        } finally {blocked.countDown();manager.shutdown();}
    }
}
