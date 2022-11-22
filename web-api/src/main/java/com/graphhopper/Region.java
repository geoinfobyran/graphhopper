package com.graphhopper;

import java.util.ArrayList;
import java.util.List;

import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

public class Region {
    private List<GHPoint> points = new ArrayList<GHPoint>();
    private List<List<GHPoint>> holes = new ArrayList<List<GHPoint>>();

    public Region setPoints(List<GHPoint> points) {
        this.points = points;
        return this;
    }

    public List<GHPoint> getPoints() {
        return points;
    }

    public void addPoint(GHPoint point) {
        this.points.add(point);
    }

    public List<List<GHPoint>> getHoles() {
        return holes;
    }

    public void addHole(List<GHPoint> hole) {
        this.holes.add(hole);
    }
}
