package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvShardPreprocessorTest {

    @Test
    void typedReaderPreservesNullEmptyAndEmbeddedNewlinesAcrossCanonicalShards() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("nullable.csv"),
                "ID;TEXT\n1;\n2;\"\"\n3;\"two\nlines\"\n");
        var options = ai.chat2db.community.domain.api.model.task.CsvOptions.defaults();
        options.setDelimiter(";");
        List<ImportManifestShard> shards = CsvShardPreprocessor.preprocess(source.toFile(), options, 0,
                tempDirectory.resolve("typed"), null, null, "T", "T", 0, 1, () -> { });
        assertEquals(3, shards.size());
        List<String> values = new ArrayList<>();
        for (ImportManifestShard shard : shards) {
            CsvShardPreprocessor.verify(Path.of(shard.getSourcePath()).toFile(), shard);
            ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader.read(
                    Path.of(shard.getSourcePath()).toFile(), CsvShardPreprocessor.canonicalOptions(options),
                    Integer.MAX_VALUE, 0, ignored -> { }, (row, number) -> values.add(row.get(1)), () -> { });
            try (CSVParser parser = CSVParser.parse(Path.of(shard.getSourcePath()), StandardCharsets.UTF_8,
                    CsvShardPreprocessor.canonicalReadFormat())) {
                var records = parser.getRecords();
                assertEquals(values.get(values.size() - 1), records.get(1).get(1));
            }
        }
        assertEquals(java.util.Arrays.asList(null, "", "two\nlines"), values);
    }

    @Test
    void cancellationDeletesOnlyThisAttemptIncludingAlreadyPublishedParts() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("cancel.csv"), "ID\n1\n2\n3\n4\n");
        Path output = Files.createDirectories(tempDirectory.resolve("cancel-parts"));
        Path unrelated = Files.writeString(output.resolve("keep.txt"), "owned elsewhere");
        assertThrows(java.util.concurrent.CancellationException.class, () -> CsvShardPreprocessor.preprocess(
                source.toFile(), ai.chat2db.community.domain.api.model.task.CsvOptions.defaults(), 0,
                output, null, null, "T", "T", 0, 1, () -> {
                    try (var paths = Files.list(output)) {
                        if (paths.anyMatch(path -> path.toString().endsWith(".csv"))) {
                            throw new java.util.concurrent.CancellationException("test cancellation during slicing");
                        }
                    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }));
        try (var paths = Files.list(output)) { assertEquals(List.of(unrelated), paths.toList()); }
        assertEquals("owned elsewhere", Files.readString(unrelated));
    }

    @TempDir
    Path tempDirectory;

    @Test
    void slicesOnlyAtLogicalRecordsAndPreservesEveryRow() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("source.csv"),
                "\uFEFFID,NAME\n1,Alice\n2,\"Bob, Jr\"\n3,Carol\n4,David\n", StandardCharsets.UTF_8);
        Path staging = tempDirectory.resolve("staging");

        List<ImportManifestShard> shards = CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "CUSTOMER", 0, 28L);

        assertTrue(shards.size() > 1);
        assertEquals(4L, shards.stream().mapToLong(ImportManifestShard::getEstimatedRows).sum());
        assertTrue(shards.stream().allMatch(shard -> shard.getExpectedChecksum().startsWith("CRC32:")));
        List<List<String>> rows = new ArrayList<>();
        for (ImportManifestShard shard : shards) {
            assertFalse(Files.exists(Path.of(shard.getSourcePath() + ".part")));
            try (CSVParser parser = CSVParser.parse(Path.of(shard.getSourcePath()),
                    StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
                List<org.apache.commons.csv.CSVRecord> records = parser.getRecords();
                assertEquals(List.of("ID", "NAME"), records.get(0).toList());
                records.stream().skip(1).map(org.apache.commons.csv.CSVRecord::toList).forEach(rows::add);
            }
        }
        assertEquals(List.of(List.of("1", "Alice"), List.of("2", "Bob, Jr"),
                List.of("3", "Carol"), List.of("4", "David")), rows);
    }

    @Test
    void skipsSourceRowsOnceBeforeShardBoundariesCountsAndChecksums() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("source-with-prefix.csv"),
                "ID,NAME\n1,discard-a\n2,discard-b\n3,Carol\n4,David\n5,Eve\n6,Frank\n",
                StandardCharsets.UTF_8);
        Path staging = tempDirectory.resolve("skipped-staging");

        List<ImportManifestShard> shards = CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "app", "public",
                "CUSTOMER", "app.public.CUSTOMER", 0, 22L, 2);

        assertTrue(shards.size() > 1);
        assertEquals(4L, shards.stream().mapToLong(ImportManifestShard::getEstimatedRows).sum());
        List<List<String>> rows = new ArrayList<>();
        for (ImportManifestShard shard : shards) {
            CsvShardPreprocessor.verify(Path.of(shard.getSourcePath()).toFile(), shard);
            try (CSVParser parser = CSVParser.parse(Path.of(shard.getSourcePath()),
                    StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
                List<org.apache.commons.csv.CSVRecord> records = parser.getRecords();
                assertEquals(List.of("ID", "NAME"), records.get(0).toList());
                records.stream().skip(1).map(org.apache.commons.csv.CSVRecord::toList).forEach(rows::add);
            }
        }
        assertEquals(List.of(List.of("3", "Carol"), List.of("4", "David"),
                List.of("5", "Eve"), List.of("6", "Frank")), rows);
    }

    @Test
    void refusesToOverwriteAnExistingShard() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("source.csv"), "ID\n1\n", StandardCharsets.UTF_8);
        Path staging = Files.createDirectories(tempDirectory.resolve("staging"));
        String fileName = CsvShardPreprocessor.shardId("CUSTOMER", 0) + ".csv";
        Files.writeString(staging.resolve(fileName), "owned", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "CUSTOMER", 0, 1024L));
        assertEquals("owned", Files.readString(staging.resolve(fileName)));
    }

    @Test
    void exactTableKeyHashKeepsCaseDistinctShardsSeparateOnCaseInsensitiveFileSystems()
            throws Exception {
        Path parent = Files.writeString(tempDirectory.resolve("parent.csv"),
                "ID\n1\n", StandardCharsets.UTF_8);
        Path child = Files.writeString(tempDirectory.resolve("child.csv"),
                "ID\n2\n", StandardCharsets.UTF_8);
        Path staging = tempDirectory.resolve("case-staging");

        List<ImportManifestShard> upper = CsvShardPreprocessor.preprocess(parent.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "app", "public",
                "Users", "app.public.Users", 0, 1024L);
        List<ImportManifestShard> lower = CsvShardPreprocessor.preprocess(child.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "app", "public",
                "users", "app.public.users", 0, 1024L);

        assertEquals(1, upper.size());
        assertEquals(1, lower.size());
        assertFalse(upper.get(0).getShardId().equals(lower.get(0).getShardId()));
        assertTrue(Files.isRegularFile(Path.of(upper.get(0).getSourcePath())));
        assertTrue(Files.isRegularFile(Path.of(lower.get(0).getSourcePath())));
    }

    @Test
    void boundsLongQualifiedKeysWhileKeepingStableCaseSensitiveIdentity() throws Exception {
        String sharedPrefix = "catalog." + "SchemaSegment.".repeat(40);
        String upperKey = sharedPrefix + "Users";
        String lowerKey = sharedPrefix + "users";

        String upperId = CsvShardPreprocessor.shardId(upperKey, 0);
        String lowerId = CsvShardPreprocessor.shardId(lowerKey, 0);

        assertEquals(upperId, CsvShardPreprocessor.shardId(upperKey, 0));
        assertFalse(upperId.equals(lowerId));
        assertEquals(upperId.substring(0, upperId.length() - 19),
                lowerId.substring(0, lowerId.length() - 19));
        assertTrue(upperId.length() <= 139);
        assertTrue((upperId + ".csv.part").length() <= 255);

        Path source = Files.writeString(tempDirectory.resolve("long-key.csv"),
                "ID\n1\n", StandardCharsets.UTF_8);
        Path staging = tempDirectory.resolve("long-key-staging");
        ImportManifestShard upper = CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "catalog", "public",
                "Users", upperKey, 0, 1024L).get(0);
        ImportManifestShard lower = CsvShardPreprocessor.preprocess(source.toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, staging, "catalog", "public",
                "users", lowerKey, 0, 1024L).get(0);

        assertEquals(upperId, upper.getShardId());
        assertEquals(lowerId, lower.getShardId());
        assertTrue(Files.isRegularFile(Path.of(upper.getSourcePath())));
        assertTrue(Files.isRegularFile(Path.of(lower.getSourcePath())));
    }
}
