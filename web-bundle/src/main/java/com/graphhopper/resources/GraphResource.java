package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphRequest;
import com.graphhopper.Region;
import com.graphhopper.config.Profile;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.Graph.algorithm.ShortestPathTree;
import com.graphhopper.Graph.algorithm.ShortestPathTree.IsoLabel;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;
import com.graphhopper.util.shapes.Polygon;

import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import java.util.function.ToDoubleFunction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

@Path("graph")
public class GraphResource {

    private static final Logger logger = LoggerFactory.getLogger(GraphResource.class);

    private final GraphHopper graphHopper;

    @Inject
    public GraphResource(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    public enum ResponseType {
        json, geojson
    }

    public static class GraphResponse {
        public SegmentWithCost[] segments;
    }

    @GET
    public GraphResponse doGet() {
        BaseGraph graph = graphHopper.getBaseGraph();
        AllEdgesIterator iter = graph.getAllEdges();
    }
}