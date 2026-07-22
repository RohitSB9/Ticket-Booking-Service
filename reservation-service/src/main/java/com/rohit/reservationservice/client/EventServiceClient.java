package com.rohit.reservationservice.client;

import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.exception.ResourceNotFoundException;
import com.rohit.reservationservice.model.SeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
public class EventServiceClient {

    private final RestClient restClient;

    public EventServiceClient(RestClient.Builder builder, @Value("${event-service.base-url}") String baseUrl) {
        // The default request factory is backed by HttpURLConnection, which
        // doesn't support PATCH. JdkClientHttpRequestFactory (java.net.http)
        // does.
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    public SeatDto getSeat(UUID eventId, UUID seatId) {
        try {
            return restClient.get()
                    .uri("/api/events/{eventId}/seats/{seatId}", eventId, seatId)
                    .retrieve()
                    .body(SeatDto.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Seat not found: " + seatId);
        }
    }

    public SeatDto updateSeatStatus(UUID eventId, UUID seatId, SeatStatus status) {
        try {
            return restClient.patch()
                    .uri("/api/events/{eventId}/seats/{seatId}", eventId, seatId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", status))
                    .retrieve()
                    .body(SeatDto.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Seat not found: " + seatId);
        }
    }
}
