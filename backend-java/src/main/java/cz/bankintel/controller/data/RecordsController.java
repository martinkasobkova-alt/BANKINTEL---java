package cz.bankintel.controller.data;

import cz.bankintel.service.data.RecordService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordsController {

    private final RecordService recordService;

    @GetMapping({"", "/"})
    public Map<String, Object> listRecords(
            @RequestParam(name = "dataset_id", required = false) String datasetId,
            @RequestParam(name = "dataset_name", required = false) String datasetName,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int skip) {
        int cappedLimit = Math.max(1, Math.min(limit, 1000));
        int cappedSkip = Math.max(0, skip);
        return recordService.listRecords(datasetId, datasetName, query, cappedLimit, cappedSkip);
    }
}
