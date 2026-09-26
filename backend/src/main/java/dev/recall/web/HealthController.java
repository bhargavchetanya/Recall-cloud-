package dev.recall.web;

import dev.recall.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthService.Report> health() {
        HealthService.Report report = health.check();
        HttpStatus status = report.status() == HealthService.Status.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(report);
    }
}
