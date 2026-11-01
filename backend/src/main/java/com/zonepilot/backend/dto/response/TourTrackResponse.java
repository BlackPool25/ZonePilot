package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.TourStop;
import java.util.List;

/**
 * Live tracing view: ordered stops with ETAs, per-leg validated route
 * geometries in creation order, and the vehicle's latest known position
 * (source NONE when no fix has ever been recorded).
 */
public class TourTrackResponse {

    private Long tourId;
    private String status;
    private List<TourResponse.StopDto> stops;
    private List<LegDto> legs;
    private PositionResponse latestPosition;

    public static class LegDto {
        private Long routeId;
        private int fromSeq; // -1 = tour origin
        private int toSeq;
        private String geometryWkt;

        public Long getRouteId() { return routeId; }
        public int getFromSeq() { return fromSeq; }
        public int getToSeq() { return toSeq; }
        public String getGeometryWkt() { return geometryWkt; }
        public void setRouteId(Long routeId) { this.routeId = routeId; }
        public void setFromSeq(int fromSeq) { this.fromSeq = fromSeq; }
        public void setToSeq(int toSeq) { this.toSeq = toSeq; }
        public void setGeometryWkt(String geometryWkt) { this.geometryWkt = geometryWkt; }
    }

    public Long getTourId() { return tourId; }
    public String getStatus() { return status; }
    public List<TourResponse.StopDto> getStops() { return stops; }
    public List<LegDto> getLegs() { return legs; }
    public PositionResponse getLatestPosition() { return latestPosition; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public void setStatus(String status) { this.status = status; }
    public void setStops(List<TourResponse.StopDto> stops) { this.stops = stops; }
    public void setLegs(List<LegDto> legs) { this.legs = legs; }
    public void setLatestPosition(PositionResponse latestPosition) {
        this.latestPosition = latestPosition;
    }

    public static TourTrackResponse of(Long tourId, String status,
                                       List<TourStop> ordered,
                                       List<LegDto> legs,
                                       PositionResponse latestPosition) {
        TourTrackResponse r = new TourTrackResponse();
        r.tourId = tourId;
        r.status = status;
        r.stops = ordered.stream().map(TourResponse.StopDto::from).toList();
        r.legs = legs;
        r.latestPosition = latestPosition;
        return r;
    }
}
