package com.availkv.storage;

import com.availkv.replication.WriteOperation;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class WALManager {

    private static final Logger log = LoggerFactory.getLogger(WALManager.class);

    // WAL file location
    @Value("${wal.path:writeAheadLog.txt}")
    private String walPath;

    private final KVStore kvStore;

    public WALManager(KVStore kvStore) {
        this.kvStore = kvStore;
    }

    @PostConstruct
    public void replayOnStartup() {
        Path path = Paths.get(walPath);

        if (!Files.exists(path)) {
            log.info("No WAL file found at {} — starting fresh", walPath);
            return;
        }

        log.info("Replaying WAL from {}...", walPath);
        List<WriteOperation> ops = readAll();
        int count = 0;

        for (WriteOperation op : ops) {
            apply(op);
            count++;
        }

        log.info("WAL replay complete — {} operations replayed, {} keys in store",
                count, kvStore.size());
    }

    public synchronized void append(WriteOperation op) {
        try {
            String line = serialize(op) + "\n";
            Files.writeString(
                    Paths.get(walPath),
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            // If we can't write the WAL, we must NOT apply the operation.
            // Throwing here causes ReplicationService to abort the write.
            throw new RuntimeException("WAL write failed — aborting operation", e);
        }
    }

    public List<WriteOperation> readAll() {
        List<WriteOperation> ops = new ArrayList<>();
        Path path = Paths.get(walPath);

        if (!Files.exists(path)) return ops;

        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    WriteOperation op = deserialize(line);
                    if (op != null) ops.add(op);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read WAL: {}", e.getMessage());
        }

        return ops;
    }

    private void apply(WriteOperation op) {
        switch (op.getType()) {
            case PUT    -> kvStore.put(op.getKey(), op.getValue());
            case DELETE -> kvStore.delete(op.getKey());
        }
    }

    private String serialize(WriteOperation op) {
        return switch (op.getType()) {
            case PUT    -> "PUT " + op.getKey() + "=" + op.getValue();
            case DELETE -> "DELETE " + op.getKey();
        };
    }

    private WriteOperation deserialize(String line) {
        try {
            if (line.startsWith("PUT ")) {
                String rest = line.substring(4);
                int eq = rest.indexOf('=');
                String key = rest.substring(0, eq);
                String value = rest.substring(eq + 1);
                return new WriteOperation(WriteOperation.Type.PUT, key, value);
            }
            if (line.startsWith("DELETE ")) {
                String key = line.substring(7).trim();
                return new WriteOperation(WriteOperation.Type.DELETE, key, null);
            }
        } catch (Exception e) {
            log.warn("Skipping malformed WAL entry: '{}' — {}", line, e.getMessage());
        }
        return null;
    }

    public String getWalPath() {
        return walPath;
    }
}