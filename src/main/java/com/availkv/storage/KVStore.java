package com.availkv.storage;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KVStore — the core storage engine.
 */
@Component
public class KVStore {

    // The actual in-memory store
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public Optional<String> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    public void put(String key, String value) {
        store.put(key, value);
    }

    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    public int size() {
        return store.size();
    }
}