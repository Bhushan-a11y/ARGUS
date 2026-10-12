package com.supplychainai.riskai;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@Service
public class NewsService {
private XmlMapper xmlMapper = new XmlMapper();
private final NewsApiData newsApiData;
    private final RestClient restClient;

    NewsService(NewsApiData newsApiData) {
        this.newsApiData = newsApiData;
        this.restClient = RestClient.create(newsApiData.url());
        
    }
public Rss getResponse(String query) {

    return restClient.get()
            .uri(uriBuilder -> uriBuilder
                    .queryParam("q", query)
                    .queryParam("hl", "en-IN")
                    .queryParam("gl", "IN")
                    .queryParam("ceid", "IN:en")
                    .build()
            )
            .exchange((request, response) -> {

               

                String body = new String(response.getBody().readAllBytes(),StandardCharsets.UTF_8);
                Rss rss = xmlMapper.readValue(body, Rss.class);
                DateTimeFormatter formatter =
                DateTimeFormatter.RFC_1123_DATE_TIME;

                int currentYear = java.time.Year.now().getValue();
                if(rss.channel().item()!=null){
                    rss.channel().item().removeIf(article -> {

                    try {
                            ZonedDateTime date =
                            ZonedDateTime.parse(article.pubDate(), formatter);

                            return date.getYear() != currentYear;

                    } catch (DateTimeParseException e) {
                        return true;
    }

});
                }
            

                

                return rss;
            });
}
//converting each article to document 
private Document toDocument(Articles articles)
{
    String cleanDescription = Jsoup.parse(articles.description()).text();
    String content = articles.title() + "\n" + cleanDescription;
    return new Document.Builder().text(content).metadata("Published date", articles.pubDate()).build();
}
//converting list of articles to list of documents for spring ai embedding 
public List<Document> getDocuments(String query)
{
    Rss rss = getResponse(query);
    if (rss.channel().item()==null) {
        return new ArrayList<>();
    }
    return rss.channel().item().stream().map(this::toDocument).toList();

}
    
@JsonIgnoreProperties(ignoreUnknown = true)
   public record Rss(
        Channel channel
) {}
@JsonIgnoreProperties(ignoreUnknown = true)
public record Channel(
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "item")
      List<Articles> item
){}
@JsonIgnoreProperties(ignoreUnknown = true)
public record Articles(
    String title,
    String pubDate,
    String description
){}
}
