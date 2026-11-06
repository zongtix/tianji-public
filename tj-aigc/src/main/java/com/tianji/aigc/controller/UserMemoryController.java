package com.tianji.aigc.controller;

import com.tianji.aigc.longterm.UserMemoryStore;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/memories")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai.long-term-memory", name = "enabled", havingValue = "true")
public class UserMemoryController {
    private final UserMemoryStore store;

    public record MemoryView(String id, String key, String content, long version, LocalDateTime updatedAt) { }
    public record Settings(boolean enabled) { }
    public record UpdateRequest(@NotNull @Min(1) Long version, @NotBlank String content) { }
    public record SettingsRequest(@NotNull Boolean enabled) { }

    @GetMapping
    public List<MemoryView> list() {
        return store.list(userId()).stream().map(memory -> new MemoryView(Long.toString(memory.id()), memory.key(),
                memory.content(), memory.version(), memory.updatedAt())).toList();
    }

    @GetMapping("/settings")
    public Settings settings() { return new Settings(store.enabled(userId())); }

    @PutMapping("/settings")
    public void settings(@Valid @RequestBody SettingsRequest request) { store.setEnabled(userId(), request.enabled()); }

    @PutMapping("/{id}")
    public void correct(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        store.correct(userId(), id, request.version(), request.content());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id, @RequestParam long version) { store.delete(userId(), id, version); }

    @DeleteMapping
    public void deleteAll() { store.deleteAll(userId()); }

    private static long userId() {
        Long id = UserContext.getUser();
        if (id == null) throw new UnauthorizedException("请先登录");
        return id;
    }
}
