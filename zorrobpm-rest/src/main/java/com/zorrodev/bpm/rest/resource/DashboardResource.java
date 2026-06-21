package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.rest.dto.DashboardDTO;
import com.zorrodev.bpm.rest.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ui")
@RequiredArgsConstructor
public class DashboardResource {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard")
    public DashboardDTO getDashboard() {
        return dashboardService.getDashboard();
    }
}
