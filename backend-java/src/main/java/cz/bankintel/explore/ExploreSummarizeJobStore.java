package cz.bankintel.explore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ExploreSummarizeJobStore {

    private final ConcurrentHashMap<String, ExploreDtos.ExploreSummarizeJob> jobs = new ConcurrentHashMap<>();

    public void put(ExploreDtos.ExploreSummarizeJob job) {
        jobs.put(job.getJobId(), job);
    }

    public Optional<ExploreDtos.ExploreSummarizeJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public Map<String, ExploreDtos.ExploreSummarizeJob> snapshot() {
        return Map.copyOf(jobs);
    }
}
