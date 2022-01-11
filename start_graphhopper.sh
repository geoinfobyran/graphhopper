#!/bin/bash

gtfs_file_name='graphs/sweden/sweden.gtfs.zip'
osm_file_name='graphs/sweden/sweden-latest.osm.pbf'

mkdir -p 'graphs/sweden'
mkdir -p 'point_sets'

if [ ! -f $gtfs_file_name ]; then
	echo 'Downloading GTFS'
	curl -o $gtfs_file_name 'https://api.resrobot.se/gtfs/sweden.zip?key=2311c3e8-a39a-445d-a648-2adc031a75fe'
fi

if [ ! -f $osm_file_name ]; then
	echo 'Downloading OSM'
	curl -o $osm_file_name 'https://download.geofabrik.de/europe/sweden-latest.osm.pbf'
fi

mvn clean package -DskipTests
java -Xmx8g -jar web/target/graphhopper-web-*.jar server reader-gtfs/config-sweden.yml
