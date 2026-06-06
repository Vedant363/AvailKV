package com.availkv.controller;

import com.availkv.storage.KVStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kv")
public class KVController {

    private final KVStore kvStore;

    public KVController(KVStore kvStore) {
        this.kvStore = kvStore;
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        return kvStore.get(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{key}")
    public ResponseEntity<String> put(
            @PathVariable String key,
            @RequestBody String value,
            @RequestParam(defaultValue = "false") boolean replicated) {
        kvStore.put(key, value);
        return ResponseEntity.ok("OK");
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<String> delete(
            @PathVariable String key,
            @RequestParam(defaultValue = "false") boolean replicated) {
        boolean deleted = kvStore.delete(key);
        return deleted
                ? ResponseEntity.ok("DELETED")
                : ResponseEntity.notFound().build();
    }
}