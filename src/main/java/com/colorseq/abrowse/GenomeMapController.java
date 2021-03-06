package com.colorseq.abrowse;

import com.colorseq.abrowse.request.BlockRequest;
import com.colorseq.abrowse.request.BrowseRequest;
import com.colorseq.abrowse.request.TrackRequest;
import com.colorseq.abrowse.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.geojson.Polygon;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

/**
 * @author Lei Kong
 */
@Controller
public class GenomeMapController {

    private String global = "global";


    @Value("${abrowse.mongodb-name-prefix}")
    private String mongodbNamePrefix;

    @Value("${abrowse.mongodb-uri}")
    private String mongodbURI;

    @RequestMapping(value = "/gmap/browse", method = RequestMethod.POST)
    @ResponseBody
    public ServerResponse browse(String browse_request, String requestIndex,HttpSession session,
                                 Authentication authentication) {

        System.out.println("DEBUG - REQUEST:" + browse_request);
        ObjectMapper objectMapper = new ObjectMapper();
        BrowseRequest browseRequest = null;
        try {
            browseRequest = objectMapper.readValue(browse_request, BrowseRequest.class);
            //System.err.println("AAA:" + objectMapper.writeValueAsString(browseRequest));
        } catch (Exception e) {
            e.printStackTrace();
            ServerResponse errorResponse = new ErrorResponse("Request converting to JSON Object error. Request String:["
                    + browse_request + "]");

            return errorResponse;

        }
        //空值判定
        if (requestIndex == null){
            requestIndex = "0";
        }

        Map<String, ConfigGenome> configGenomeMap = null;
        try {
            configGenomeMap = (Map<String, ConfigGenome>) session.getAttribute(SessionKeys.CONFIG_GENOME_MAP);
        } catch (Exception e) {
            e.printStackTrace();
        }

        String genome = browseRequest.getGenome();
        System.out.println("DEBUG - genome:" + genome);
        ConfigGenome currentConfigGenome = configGenomeMap.get(genome);
        //ConfigGenome globalConfigGenome = configGenomeMap.get(global);

        MongoClientURI mongoClientURI = new MongoClientURI(mongodbURI);
        MongoClient mongoClient = new MongoClient(mongoClientURI);

        String dbName = new StringBuilder(mongodbNamePrefix).append(genome).toString();
	System.out.println("AAAAAAAAAAAAAAA1 dbName:" + dbName);
        MongoDatabase database = mongoClient.getDatabase(dbName);

        String chrName = browseRequest.getChrName();
        ConfigChromosome chromosomeConfig = currentConfigGenome.getChromosomeMap().get(chrName);
	System.out.println("AAAAAAAAAAAAAAA2 chrName:" + chrName);
        int chrLen = chromosomeConfig.getLength();
	System.out.println("AAAAAAAAAAAAAAA3 chrLen:" + chrLen);
        int chrCode = chromosomeConfig.getCode();
	System.out.println("AAAAAAAAAAAAAAA4 chrCode:" + chrCode);

        BrowseResponse browseResponse = new BrowseResponse();
        browseResponse.setGenome(genome);
        browseResponse.setChrName(chrName);
        browseResponse.setChrLength(chrLen);
        browseResponse.setRequestIndex(Integer.valueOf(requestIndex));


        List<TrackRequest> trackRequestList = browseRequest.getTrackRequests();
        for(TrackRequest trackRequest : trackRequestList) {
            String trackName = trackRequest.getTrackName();
            System.out.println("DEBUG - Retrieving data from track:" + trackName);
            String trackGroupName = trackRequest.getTrackGroupName();
            ConfigTrackGroup configTrackGroup = currentConfigGenome.getTrackGroupMap().get(trackGroupName);
            ConfigTrack configTrack = configTrackGroup.getTrackMap().get(trackName);
            MongoCollection<Document> collection = database.getCollection(trackName);
            TrackResponse trackResponse = new TrackResponse();
            trackResponse.setTrackName(trackName);
            trackResponse.setTrackDisplayName(configTrack.getDisplayName());
            trackResponse.setViewName(trackRequest.getViewName());
            trackResponse.setyIndex(trackRequest.getyIndex());

            List<BlockRequest> blockRequestList = trackRequest.getBlockRequests();

            int recordNumber = 0;
            for (BlockRequest blockRequest : blockRequestList) {
                int start = blockRequest.getStart();
                int end = blockRequest.getEnd();

                if(start >= chrLen){ start = chrLen-1; }
                if(end >= chrLen){ end = chrLen; }

                BlockResponse blockResponse = new BlockResponse(start, end);

                //如果出现输入边界值的情况，不加载数据
                /*if (end >= chrLen || start <= 0){
                    continue;
                }*/
//                if(end >= start && end<= chrLen && start >=0){}
                Polygon requestedPolygon = GeoSpatialUtils.getQueryRectangle(start, end, chrLen, chrCode);
                System.out.println("requestedPolygon========"+requestedPolygon);
                FindIterable<Document> findIterable =
                        collection.find(Filters.geoIntersects(GtfField.location, requestedPolygon));
                MongoCursor<Document> cursor = findIterable.iterator();
                while (cursor.hasNext()) {
                    ++ recordNumber;
                    Document document = cursor.next();
                    blockResponse.addEntry(document);
                }
                trackResponse.addBlockResponse(blockResponse);
            }
            System.out.println("DEBUG - " + recordNumber + " retrieved from " + trackName);
            browseResponse.addTrackResponse(trackResponse);
        }

        /*
        //7.914156157016106
        //7.940907826832441
        //db.neighborhoods.findOne({ geometry: { $geoIntersects: { $geometry: { type: "LineString",
        coordinates: [ [ 7.914156157016106, 1 ], [ 7.940907826832441, 1 ] ] } } } })
        */
        return browseResponse;
    }
}
