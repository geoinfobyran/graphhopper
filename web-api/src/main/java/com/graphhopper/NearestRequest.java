package com.graphhopper;

import java.util.ArrayList;
import java.util.List;

import com.graphhopper.util.shapes.GHPoint;

public class NearestRequest {
    private List<GHPoint> points = new ArrayList<GHPoint>();

    public NearestRequest setPoints(List<GHPoint> points) {
        this.points = points;
        return this;
    }

    public List<GHPoint> getPoints() {
        return points;
    }

    public void addPoint(GHPoint point) {
        this.points.add(point);
    }
}