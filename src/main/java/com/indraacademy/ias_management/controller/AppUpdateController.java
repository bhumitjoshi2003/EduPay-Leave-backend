package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AppUpdateInfoResponse;
import com.indraacademy.ias_management.service.AppUpdateConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated — the Android app can check for updates before login (e.g. on
 * splash). Returns only static, non-sensitive version thresholds; no build/environment
 * internals, no secrets. Nothing here reads or trusts a client-supplied version.
 */
@RestController
public class AppUpdateController {

    @Autowired private AppUpdateConfig appUpdateConfig;

    @GetMapping("/api/public/app-update")
    public AppUpdateInfoResponse getAppUpdateInfo() {
        return appUpdateConfig.toResponse();
    }
}
