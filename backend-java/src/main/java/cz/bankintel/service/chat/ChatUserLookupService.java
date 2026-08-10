package cz.bankintel.service.chat;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.UserRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatUserLookupService {

    private final UserRepository userRepository;

    public List<Map<String, Object>> searchUsers(String actorUserId, String query, int limit) {
        String needle = query == null ? "" : query.strip();
        if (needle.length() < 2) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        return userRepository
                .searchForChat(actorUserId, needle, PageRequest.of(0, capped))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public Map<String, Map<String, Object>> loadUserMap(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct =
                userIds.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (UserEntity user : userRepository.findAllById(distinct)) {
            out.put(user.getId(), toSummary(user));
        }
        for (String uid : distinct) {
            out.putIfAbsent(uid, fallbackUser(uid));
        }
        return out;
    }

    public Map<String, Object> toSummary(UserEntity user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("name", user.getName() != null && !user.getName().isBlank() ? user.getName() : "Uživatel");
        row.put("email", user.getEmail() != null ? user.getEmail() : "");
        return row;
    }

    public Map<String, Object> fallbackUser(String userId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", userId);
        row.put("name", "Uživatel");
        row.put("email", "");
        return row;
    }

    public List<String> sortedUniqueIds(Collection<String> ids) {
        return ids.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
