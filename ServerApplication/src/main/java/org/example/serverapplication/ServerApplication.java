package org.example.serverapplication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class ServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServerApplication.class, args);
	}

	@RestController
	@RequestMapping("/messages")
	public static class MessageController {

		private final List<Map<String, Object>> messages = Collections.synchronizedList(new ArrayList<>());
		private final AtomicInteger idGenerator = new AtomicInteger();

		@PostMapping
		public ResponseEntity<?> postMessage(@RequestBody Map<String, Object> message) {
			message.put("message-id", idGenerator.incrementAndGet());
			messages.add(0, message); // Add new message at the beginning
			return ResponseEntity.ok().body(message);
		}

		@GetMapping
		public ResponseEntity<List<Map<String, Object>>> listMessages(@RequestParam(required = false) Integer count) {
			if (count == null || count > messages.size()) {
				count = messages.size();
			}
			List<Map<String, Object>> responseMessages = messages.subList(0, count);
			return new ResponseEntity<>(responseMessages, HttpStatus.OK);
		}
	}
}
