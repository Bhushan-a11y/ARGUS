package com.supplychainai.riskai;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service

public class RedisService {
    private final RedisTemplate redisTemplate;

    RedisService(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, String value, Long ttl)
    {
        try {
            redisTemplate.opsForValue().set(key,value,ttl,TimeUnit.MINUTES);
        } catch (Exception e) {
            System.out.println("Cannot add data to redis ");
            redisTemplate.opsForValue().set(key, value, ttl);
        }
    }

    public <T> T get(String key, Class<T> responseClass)
    {
        ObjectMapper objectMapper = new ObjectMapper();
        Object object =  redisTemplate.opsForValue().get(key);
        if(object==null)
        {
            return null;
        }
        try {
        return objectMapper.readValue(object.toString(),responseClass);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error while getting response from redis");
            return null;
        }
      

    }
}
