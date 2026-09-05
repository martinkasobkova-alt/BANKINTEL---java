package cz.bankintel.controller.data;

import cz.bankintel.service.data.DatasetService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetsController {

    private final DatasetService datasetService;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listDatasets() {
        return datasetService.listDatasets();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getDataset(@PathVariable String id) {
        return datasetService.getDataset(id);
    }
}
