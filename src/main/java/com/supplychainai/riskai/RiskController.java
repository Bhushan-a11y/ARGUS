package com.supplychainai.riskai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplychainai.riskai.NewsService.Rss;
@RestController

public class RiskController {
    @Autowired
    private VectorStore vectorStore;
    private final RiskService riskService;
    
    private final RssAsyncService rssAsyncService;
    private final RedisService redisService;
    private final NewsService newsService;

   

    private final ChatClient chatClient;

    RiskController(ChatClient.Builder chatClient, NewsService newsController, RedisService redisService, RssAsyncService rssAsyncService, RiskService riskService) {
        this.chatClient = chatClient.build();
        this.newsService = newsController;
        this.redisService = redisService;
        this.rssAsyncService = rssAsyncService;
        this.riskService = riskService;
    }

    String qwenResponse;
    Response response;

    @GetMapping("/query")
    public Response getQueries() throws JsonProcessingException { 
        return riskService.getQ();
    }

    public record Query( String query) {}
    public record Response(List<Query> queries) {}



    /*@GetMapping("/rss")
   public List<Rss> getNewsForEachQuery()
   {
     List<Rss> result = new ArrayList<>();
    for(Query query:response.queries)
    {
       
        Rss rss = newsService.getResponse(query.query);
        result.add(rss);
        
    }
    return result;
   }*/


    

   @GetMapping("/async")
   public List<Document> fetchNews()
   {
    riskService.getQ();
    
   return riskService.fetch();

   }


   ObjectMapper objectMapper = new ObjectMapper();
   String rssJson;



   @GetMapping("/risk")
   public List<RiskResponse> getRiskAnalysis()
   {
    return riskService.getRiskScore();
   }

   @GetMapping ("/automate")
   public List<RiskResponse> automate()
   {
    riskService.getQ();
    return riskService.getRiskScore();
   }
  
   
    

}
