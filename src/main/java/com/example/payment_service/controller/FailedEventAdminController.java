package com.example.payment_service.controller;

import com.example.payment_service.service.FailedEventReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/failed-events")
@RequiredArgsConstructor
public class FailedEventAdminController {

    private final FailedEventReplayService replayService;

    @PostMapping("/retry-all")
    public ResponseEntity<String> retryAll(@RequestParam String topic) {
        int count = replayService.retryAllByTopic(topic);
        return ResponseEntity.ok(String.format("토픽 [%s]의 에러 메시지 %d건이 성공적으로 재발행되었습니다.", topic, count));
    }

    @PostMapping("/{eventId}/ignore")
    public ResponseEntity<String> ignoreEvent(@PathVariable Long eventId) {
        replayService.ignoreEvent(eventId);
        return ResponseEntity.ok("에러 메시지가 폐기(IGNORED) 처리되었습니다.");
    }
}