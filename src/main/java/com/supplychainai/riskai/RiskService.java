package com.supplychainai.riskai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.checkerframework.checker.units.qual.s;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.supplychainai.riskai.RiskController.Query;
import com.supplychainai.riskai.RiskController.Response;

import tools.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
public class RiskService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingService embeddingService;
    private final PgVectorStore pgVectorStore;
    private final RssAsyncService rssAsyncService;
    private final ChatClient chatClient;
    private final RedisService redisService;
   

    RiskService(RedisService redisService, ChatClient.Builder chatClient, RssAsyncService rssAsyncService, PgVectorStore pgVectorStore, EmbeddingModel embeddingModel, EmbeddingService embeddingService) {
        this.redisService = redisService;
        this.chatClient = chatClient.build();
        this.rssAsyncService = rssAsyncService;
        this.pgVectorStore = pgVectorStore;
        this.embeddingModel = embeddingModel;
        this.embeddingService = embeddingService;
    }

    
    @Value("classpath:prompts/searchquerydemo.st")    
    public Resource queryprompt;

     @Value("classpath:prompts/riskanalysis.st")
    public Resource riskPrompt;

  

    public List<Document> fetch() {
         List<Document> currDocuments = new ArrayList<>();
    Map<Integer,List<Document>> eventMap = new HashMap<>();
         List<Document> multi = new ArrayList<>();
        
         List<CompletableFuture<List<Document>>> future = new ArrayList<>();
    for(Query query : response.queries())
    {
        future.add(rssAsyncService.fetch(query.query()));

    }
    CompletableFuture<Void> allResults = CompletableFuture.allOf(future.toArray(new CompletableFuture[0]));
    allResults.join();
    List<Document> documents = (future.stream().map(x->x.join()).flatMap(List::stream).toList());
    currDocuments = documents;
    TokenTextSplitter textSplitter = TokenTextSplitter.builder().build();
    List<Document> chunkDoc = textSplitter.apply(documents);
    pgVectorStore.add(chunkDoc);
    List<float[]> embeddings = new ArrayList<>();
    List<ArticlesDTO> aDtos = new ArrayList<>();
    for(Document docs : currDocuments)
    {
        float[] embeds = embeddingModel.embed(docs.getText());
        embeddings.add(embeds);
        ArticlesDTO articlesDTO = new ArticlesDTO();
        articlesDTO.setArticleId(docs.getId());
        articlesDTO.setArticleText(docs.getText());
        articlesDTO.setArticleEmbeddings(embeds);
        aDtos.add(articlesDTO);
    }
    System.out.println(documents.size());
    System.out.println(embeddings.size());
    DSU dsu = new DSU(embeddings.size());
    for(int i = 0;i<embeddings.size();i++)
    {
        for(int j = i+1;j<embeddings.size();j++)
        {
            double similarity = embeddingService.cosineSimalarity(aDtos.get(i).getArticleEmbeddings(),aDtos.get(j).getArticleEmbeddings());
            if(similarity>0.7669)
                dsu.union(i, j);
        }
    }
    for(int i = 0;i<currDocuments.size();i++)
    {
        int root = dsu.find(i);
        eventMap.computeIfAbsent(root, k->new ArrayList<>())
        .add(currDocuments.get(i));
        
    }
    for(List<Document> group : eventMap.values())
    {
        multi.add(group.get(0));
    }
    System.out.println(multi.size());


    return multi;
    }

    

     Response response;
    public Response getQ() {
        String qwenResponse;
       ObjectMapper objectMapper = new ObjectMapper();
       response = redisService.get("qwenResponse", Response.class);
        if (response==null) {
            QueryResponse queryResponse =  chatClient.prompt().user(queryprompt).call().entity(QueryResponse.class);
            System.out.println(queryResponse);
        response = new Response(queryResponse.queries().stream().map(Query::new).toList());
        String json = objectMapper.writeValueAsString(response);
         redisService.set("qwenResponse",json , 60L);
        return response;
       
        }
        else{
            return redisService.get("qwenResponse", Response.class);
        }
        
    }
    

     public List<RiskResponse> getRiskScore()
   {
    List<RiskResponse> responses = new ArrayList<>();
    getQ();
    List<Document> multi = fetch();
    for(Document doc : multi)
    {

    RiskResponse response =  chatClient.prompt().system("You are an expert supply-chain risk assessment assistant.\n" + //
                "\n" + //
                "Analyze the provided information objectively and do not invent facts.\n" + //
                "Follow the user's instructions and required output format exactly.")
    .user(u->u.
        param("supplierName","Shenzhen Semiconductor Components Ltd.")
        .param("supplierLocation", "Shenzhen, Guangdong, China")
        .param("goods", "semiconductor chips, microcontrollers, PCB components")
        .param("destination", "Bangalore, India")
        .param("transport","sea, road" )
        .param("rssJson", multi.get(1))
        .text(riskPrompt))
        .call().entity(RiskResponse.class);
        responses.add(response);
        break;

    }
    return  responses;

   }



}
