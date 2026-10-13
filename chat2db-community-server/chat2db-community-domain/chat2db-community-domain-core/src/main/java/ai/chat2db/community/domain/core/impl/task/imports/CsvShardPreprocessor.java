package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

/** Physically slices an admitted CSV at logical-record boundaries and emits UTF-8 shards. */
public final class CsvShardPreprocessor {

    private static final int MAX_READABLE_TABLE_PREFIX_LENGTH = 120;

    private CsvShardPreprocessor() {
    }

    /** Canonical shards preserve quoted empty strings separately from unquoted null fields. */
    static CSVFormat canonicalReadFormat() {
        return CSVFormat.DEFAULT.builder().setNullString("").setQuoteMode(QuoteMode.ALL_NON_NULL).build();
    }

    static CsvOptions canonicalOptions(CsvOptions source) {
        CsvOptions result = (source == null ? CsvOptions.defaults() : source).validate();
        result.setEncoding("UTF-8");
        result.setDelimiter(",");
        result.setQuote("\"");
        result.setEscape("\"");
        result.setNewline("LF");
        result.setHasHeader(true);
        result.setHeaderRow(1);
        result.setDataStartRow(2);
        result.setDataEndRow(null);
        return result;
    }

    /** Uses the same streaming source reader as preview/import; only selected rows reach shards. */
    public static List<ImportManifestShard> preprocess(File source, CsvOptions options, int minimumColumns,
            Path outputDirectory, String databaseName, String schemaName, String tableName, String tableKey,
            int layer, long targetBytes, Runnable checkCancelled) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()
                || StringUtils.isBlank(tableName) || layer < 0 || targetBytes <= 0L) {
            throw new IllegalArgumentException("CSV preprocessing requires readable source, table and shard size");
        }
        Files.createDirectories(outputDirectory);
        List<Path> created = new ArrayList<>();
        List<ImportManifestShard> result = new ArrayList<>();
        class Slicer {
            ShardWriter current;
            List<String> header;
            int number;
            void head(Map<Integer, String> values) {
                header = new ArrayList<>(values.values());
                try {
                    current = openShard(outputDirectory, databaseName, schemaName, tableName,
                            tableKey, layer, number++, header, created);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            void row(Map<Integer, String> values, Integer rowNumber) {
                List<String> cells = new ArrayList<>(values.values());
                long bytes = estimateUtf8Bytes(cells);
                try {
                    if (current.rows > 0 && current.estimatedBytes + bytes > targetBytes) {
                        result.add(current.publish());
                        current = openShard(outputDirectory, databaseName, schemaName, tableName,
                                tableKey, layer, number++, header, created);
                    }
                    current.write(cells, bytes);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        }
        Slicer slicer = new Slicer();
        try {
            CsvImportReader.read(source, options, Integer.MAX_VALUE, minimumColumns,
                    slicer::head, slicer::row, checkCancelled);
            if (slicer.current == null) {
                throw new IllegalArgumentException("CSV preprocessing requires a selected header or data record");
            }
            result.add(slicer.current.publish());
            return List.copyOf(result);
        } catch (IOException | RuntimeException failure) {
            if (slicer.current != null) slicer.current.closeQuietly();
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                    Files.deleteIfExists(partPath(path));
                } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    public static List<ImportManifestShard> preprocess(File source, Charset sourceCharset,
            CSVFormat sourceFormat, Path outputDirectory, String tableName, int layer, long targetBytes)
            throws IOException {
        return preprocess(source, sourceCharset, sourceFormat, outputDirectory, null, null, tableName,
                tableName, layer, targetBytes);
    }

    public static List<ImportManifestShard> preprocess(File source, Charset sourceCharset,
            CSVFormat sourceFormat, Path outputDirectory, String databaseName, String schemaName,
            String tableName, String tableKey, int layer, long targetBytes) throws IOException {
        return preprocess(source, sourceCharset, sourceFormat, outputDirectory, databaseName, schemaName,
                tableName, tableKey, layer, targetBytes, 0);
    }

    public static List<ImportManifestShard> preprocess(File source, Charset sourceCharset,
            CSVFormat sourceFormat, Path outputDirectory, String databaseName, String schemaName,
            String tableName, String tableKey, int layer, long targetBytes, int skipRows) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IllegalArgumentException("CSV preprocessing requires a readable source file");
        }
        if (sourceCharset == null || sourceFormat == null) {
            throw new IllegalArgumentException("CSV preprocessing requires an explicit charset and dialect");
        }
        if (StringUtils.isBlank(tableName) || layer < 0 || targetBytes <= 0L) {
            throw new IllegalArgumentException("CSV preprocessing requires table, layer and positive shard size");
        }
        Files.createDirectories(outputDirectory);
        List<Path> created = new ArrayList<>();
        List<ImportManifestShard> result = new ArrayList<>();
        ShardWriter current = null;
        try (CSVParser parser = CSVParser.parse(source.toPath(), sourceCharset, sourceFormat)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("CSV preprocessing requires a header record");
            }
            List<String> header = new ArrayList<>(records.next().toList());
            if (!header.isEmpty()) {
                header.set(0, StringUtils.removeStart(header.get(0), "\uFEFF"));
            }
            int shardNumber = 0;
            current = openShard(outputDirectory, databaseName, schemaName, tableName, tableKey,
                    layer, shardNumber++, header, created);
            int remainingSkipRows = Math.max(0, skipRows);
            while (records.hasNext()) {
                CSVRecord record = records.next();
                if (remainingSkipRows > 0) {
                    remainingSkipRows--;
                    continue;
                }
                long recordBytes = estimateUtf8Bytes(record);
                if (current.rows > 0L && current.estimatedBytes + recordBytes > targetBytes) {
                    result.add(current.publish());
                    current = openShard(outputDirectory, databaseName, schemaName, tableName, tableKey,
                            layer, shardNumber++, header, created);
                }
                current.write(record, recordBytes);
            }
            result.add(current.publish());
            current = null;
            return List.copyOf(result);
        } catch (Exception failure) {
            if (current != null) {
                current.closeQuietly();
            }
            for (Path path : created) {
                Files.deleteIfExists(path);
                Files.deleteIfExists(partPath(path));
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IOException("CSV preprocessing failed", failure);
        }
    }

    private static ShardWriter openShard(Path outputDirectory, String databaseName, String schemaName,
            String tableName, String tableKey, int layer, int shardNumber,
            List<String> header, List<Path> created) throws IOException {
        String effectiveTableKey = StringUtils.defaultIfBlank(tableKey, tableName).trim();
        String shardId = shardId(effectiveTableKey, shardNumber);
        Path target = outputDirectory.resolve(shardId + ".csv").toAbsolutePath().normalize();
        if (!target.startsWith(outputDirectory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("CSV shard target escapes the staging directory");
        }
        if (Files.exists(target) || Files.exists(partPath(target))) {
            throw new IllegalStateException("CSV shard target already exists: " + target.getFileName());
        }
        created.add(target);
        return new ShardWriter(shardId, databaseName, schemaName, tableName.trim(), effectiveTableKey,
                layer, target, header);
    }

    static String shardId(String tableKey, int shardNumber) {
        String exactKey = StringUtils.trimToEmpty(tableKey);
        String safeTable = exactKey.replaceAll("[^A-Za-z0-9._-]", "_");
        String readableTable = StringUtils.left(safeTable, MAX_READABLE_TABLE_PREFIX_LENGTH);
        String identity = UUID.nameUUIDFromBytes(exactKey.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12);
        return readableTable.toLowerCase(Locale.ROOT) + "-" + identity + "-"
                + String.format(Locale.ROOT, "%05d", shardNumber);
    }

    private static Path partPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static long estimateUtf8Bytes(Iterable<String> record) {
        long bytes = 1L;
        for (String value : record) {
            bytes = Math.addExact(bytes, StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8).length + 3L);
        }
        return bytes;
    }

    private static void updateChecksum(CRC32 checksum, Iterable<String> record) {
        for (String value : record) {
            // Keep the existing manifest checksum contract: logical text, null/empty both length 0.
            // Quoting preserves their import meaning, while existing manifests remain verifiable.
            byte[] bytes = StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8);
            byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
            checksum.update(length, 0, length.length);
            checksum.update(':');
            checksum.update(bytes, 0, bytes.length);
            checksum.update('|');
        }
        checksum.update('\n');
    }

    static ShardVerification verify(File source, ImportManifestShard shard) throws IOException {
        if (source == null || shard == null || StringUtils.isBlank(shard.getExpectedChecksum())) {
            throw new IllegalArgumentException("CSV shard verification requires a file and expected checksum");
        }
        ShardVerification actual = inspect(source);
        if (actual.rows() != shard.getEstimatedRows()
                || !actual.checksum().equalsIgnoreCase(shard.getExpectedChecksum())) {
            throw new IllegalStateException("CSV shard row count or checksum does not match manifest: "
                    + shard.getShardId());
        }
        return actual;
    }

    static ShardVerification inspect(File source) throws IOException {
        CRC32 checksum = new CRC32();
        long rows = 0L;
        try (CSVParser parser = CSVParser.parse(source.toPath(), StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("CSV shard requires a header record");
            }
            records.next();
            while (records.hasNext()) {
                updateChecksum(checksum, records.next());
                rows++;
            }
        }
        return new ShardVerification(rows,
                "CRC32:" + Long.toHexString(checksum.getValue()).toUpperCase(Locale.ROOT));
    }

    record ShardVerification(long rows, String checksum) {
    }

    private static final class ShardWriter {
        private final String shardId;
        private final String databaseName;
        private final String schemaName;
        private final String tableName;
        private final String tableKey;
        private final int layer;
        private final Path target;
        private final Path temporary;
        private final BufferedWriter writer;
        private final CSVPrinter printer;
        private final CRC32 checksum = new CRC32();
        private long rows;
        private long estimatedBytes;
        private boolean closed;

        private ShardWriter(String shardId, String databaseName, String schemaName, String tableName,
                String tableKey, int layer, Path target, List<String> header) throws IOException {
            this.shardId = shardId;
            this.databaseName = databaseName;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.tableKey = tableKey;
            this.layer = layer;
            this.target = target;
            this.temporary = partPath(target);
            this.writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
            this.printer = CSVFormat.DEFAULT.builder().setRecordSeparator("\n")
                    .setQuoteMode(QuoteMode.ALL_NON_NULL).build().print(writer);
            printer.printRecord(header);
            estimatedBytes = header.stream().mapToLong(value -> StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8).length + 3L)
                    .sum() + 1L;
        }

        private void write(Iterable<String> record, long recordBytes) throws IOException {
            printer.printRecord(record);
            updateChecksum(checksum, record);
            estimatedBytes = Math.addExact(estimatedBytes, recordBytes);
            rows++;
        }

        private ImportManifestShard publish() throws IOException {
            close();
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, target);
            }
            return ImportManifestShard.builder()
                    .shardId(shardId)
                    .databaseName(databaseName)
                    .schemaName(schemaName)
                    .tableName(tableName)
                    .tableKey(tableKey)
                    .layer(layer)
                    .sourcePath(target.toString())
                    .estimatedRows(rows)
                    .expectedChecksum("CRC32:" + Long.toHexString(checksum.getValue()).toUpperCase(Locale.ROOT))
                    .dependencyShardIds(List.of())
                    .build();
        }

        private void close() throws IOException {
            if (!closed) {
                closed = true;
                printer.close();
            }
        }

        private void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // The caller deletes only this invocation's .part and completed shard files.
            }
        }
    }
}
