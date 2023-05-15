package com.graphhopper;

import java.util.ArrayList;
import java.util.List;

public class IsochroneRequest {
    private List<Region> regions = new ArrayList<Region>();
    private String profileName = "";
    private long timeLimitInSeconds = -1;
    private long distanceLimitInMeters = -1;
    private boolean useDistanceAsWeight = false;

    public IsochroneRequest setRegions(List<Region> regions) {
        this.regions = regions;
        return this;
    }

    public List<Region> getRegions() {
        return regions;
    }

    public void addRegion(Region region) {
        this.regions.add(region);
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

    public IsochroneRequest setDistanceLimitInMeters(long distanceLimitInMeters) {
        this.distanceLimitInMeters = distanceLimitInMeters;
        return this;
    }

    public long getDistanceLimitInMeters() {
        return distanceLimitInMeters;
    }

    public IsochroneRequest setUseDistanceAsWeight(boolean useDistanceAsWeight) {
        this.useDistanceAsWeight = useDistanceAsWeight;
        return this;
    }

    public boolean getUseDistanceAsWeight() {
        return useDistanceAsWeight;
    }
}
