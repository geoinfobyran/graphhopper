package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.IsochroneRequest;
import com.graphhopper.config.Profile;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
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
import java.util.List;

import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

@Path("isochrone")
public class IsochroneResource {

    private static final Logger logger = LoggerFactory.getLogger(IsochroneResource.class);

    private final GraphHopper graphHopper;
    private final Triangulator triangulator;
    private final ProfileResolver profileResolver;

    @Inject
    public IsochroneResource(GraphHopper graphHopper, Triangulator triangulator, ProfileResolver profileResolver) {
        this.graphHopper = graphHopper;
        this.triangulator = triangulator;
        this.profileResolver = profileResolver;
    }

    public enum ResponseType {
        json, geojson
    }

    public static class ResponseWithCosts {
        public SegmentWithCost[] segments;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ResponseWithCosts doPost(@NotNull IsochroneRequest request) {
        StopWatch sw = new StopWatch().start();
        PMap hintsMap = new PMap();
        hintsMap.putObject(Parameters.CH.DISABLE, true);
        hintsMap.putObject(Parameters.Landmark.DISABLE, true);

        Profile profile = graphHopper.getProfile(request.getProfileName());
        if (profile == null)
            throw new IllegalArgumentException(
                    "The requested profile '" + request.getProfileName() + "' does not exist");
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        BaseGraph graph = graphHopper.getBaseGraph();
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        BooleanEncodedValue inSubnetworkEnc = graphHopper.getEncodingManager()
                .getBooleanEncodedValue(Subnetwork.key(request.getProfileName()));
        assert (!hintsMap.has(Parameters.Routing.BLOCK_AREA));
        List<Snap> snaps = new ArrayList<Snap>();
        for (GHPoint point : request.getPoints()) {
            Snap snap = locationIndex.findClosest(point.lat, point.lon,
                    new DefaultSnapFilter(weighting, inSubnetworkEnc));
            if (!snap.isValid())
                throw new IllegalArgumentException("Point not found:" + point);
            snaps.add(snap);
        }
        QueryGraph queryGraph = QueryGraph.create(graph, snaps);
        TraversalMode traversalMode = profile.isTurnCosts() ? EDGE_BASED : NODE_BASED;
        ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, queryGraph.wrapWeighting(weighting),
                false, traversalMode);

        double limit;
        double normalization_factor;
        ToDoubleFunction<ShortestPathTree.IsoLabel> fz;
        if (request.getDistanceLimitInMeters() > 0) {
            normalization_factor = 1.0;
            limit = request.getDistanceLimitInMeters();
            shortestPathTree.setDistanceLimit(limit);
            fz = l -> l.distance;
        } else {
            normalization_factor = 0.001;
            limit = request.getTimeLimitInSeconds() / normalization_factor;
            shortestPathTree.setTimeLimit(limit);
            fz = l -> l.time;
        }
        assert (limit > 0);
        ArrayList<Double> zs = new ArrayList<>();
        zs.add(limit);

        final NodeAccess na = queryGraph.getNodeAccess();
        List<Integer> fromNodes = new ArrayList<Integer>();
        for (Snap snap : snaps) {
            fromNodes.add(snap.getClosestNode());
        }
        Collection<Coordinate> sites = new ArrayList<>();
        Collection<SegmentWithCost> segments = new ArrayList<>();
        shortestPathTree.search(fromNodes, label -> {
            double exploreValue = fz.applyAsDouble(label);
            double lat = na.getLat(label.node);
            double lon = na.getLon(label.node);
            Coordinate site = new Coordinate(lon, lat);
            site.z = exploreValue;
            sites.add(site);

            // TODO: Visit all edges, not just tree edges.
            if (label.parent != null) {
                double parentExploreValue = fz.applyAsDouble(label.parent);
                EdgeIteratorState edge = queryGraph.getEdgeIteratorState(label.edge, label.node);
                ArrayList<CoordinateWithCost> coordinates = new ArrayList<>();
                double c1 = parentExploreValue * normalization_factor;
                double c2 = exploreValue * normalization_factor;
                coordinates.add(new CoordinateWithCost(
                        na.getLat(label.parent.node),
                        na.getLon(label.parent.node),
                        c1));
                PointList points = edge.fetchWayGeometry(FetchMode.PILLAR_ONLY);
                for (int i = 0; i < points.size(); i++) {
                    coordinates.add(new CoordinateWithCost(
                            points.getLat(i),
                            points.getLon(i),
                            (c1 * (points.size() - i)) / (points.size() + 1) + (c2 * (i + 1)) / (points.size() + 1)));
                }
                coordinates.add(new CoordinateWithCost(
                        lat,
                        lon,
                        c2));
                for (int i = 0; i + 1 < coordinates.size(); i++) {
                    segments.add(new SegmentWithCost(coordinates.get(i), coordinates.get(i + 1)));
                }
            }
        });
        logger.info("took: " + sw.getSeconds() + ", visited nodes:" + shortestPathTree.getVisitedNodes());
        return wrapNodesWithCosts(segments.toArray(new SegmentWithCost[0]));
    }

    private ResponseWithCosts wrapNodesWithCosts(SegmentWithCost[] segments) {
        Arrays.sort(segments);
        ResponseWithCosts response = new ResponseWithCosts();
        response.segments = segments;
        return response;
    }

    private Polygon heuristicallyFindMainConnectedComponent(MultiPolygon multiPolygon, Point point) {
        int maxPoints = 0;
        Polygon maxPolygon = null;
        for (int j = 0; j < multiPolygon.getNumGeometries(); j++) {
            Polygon polygon = (Polygon) multiPolygon.getGeometryN(j);
            if (polygon.contains(point)) {
                return polygon;
            }
            if (polygon.getNumPoints() > maxPoints) {
                maxPoints = polygon.getNumPoints();
                maxPolygon = polygon;
            }
        }
        return maxPolygon;
    }

    /**
     * We want to specify a tolerance in something like meters, but we need it in
     * unprojected lat/lon-space.
     * This is more correct in some parts of the world, and in some directions, than
     * in others.
     *
     * @param distanceInMeters distance in meters
     * @return "distance" in degrees
     */
    static double degreesFromMeters(double distanceInMeters) {
        return distanceInMeters / DistanceCalcEarth.METERS_PER_DEGREE;
    }

}