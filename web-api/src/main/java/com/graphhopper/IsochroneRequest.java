package com.graphhopper;

import java.util.ArrayList;
import java.util.List;

import com.graphhopper.util.shapes.GHPoint;

public class IsochroneRequest {
    private List<GHPoint> points = new ArrayList<GHPoint>();
    private String profileName = "";
    private long timeLimitInSeconds = 0;

    public IsochroneRequest setPoints(List<GHPoint> points) {
        this.points = points;
        return this;
    }

    public List<GHPoint> getPoints() {
        return points;
    }

    public void addPoint(GHPoint point) {
        this.points.add(point);
    }

    public IsochroneRequest setProfileName(String profileName) {
        this.profileName = profileName;
        return this;
    }

    public String getProfileName() {
        return profileName;
    }

    public IsochroneRequest setTimeLimitInSeconds(long timeLimitInSeconds) {
        this.timeLimitInSeconds = timeLimitInSeconds;
        return this;
    }

    public long getTimeLimitInSeconds() {
        return timeLimitInSeconds;
    }
}
