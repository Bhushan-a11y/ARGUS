package com.supplychainai.riskai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.supplychainai.riskai.NewsService.Rss;

@Service
public class RssAsyncService{
    private final NewsService newsService;

    RssAsyncService(NewsService newsService) {
        this.newsService = newsService;
    }
    @Async("RssExecutor")
    public CompletableFuture<List<Document>> fetch(String query)
    {
        System.out.println("Async Thread : "+ Thread.currentThread().getName());
        List<Document> documents = newsService.getDocuments(query);
        return CompletableFuture.completedFuture(documents);
    }

}
