package com.supplychainai.riskai;

import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
    public double cosineSimalarity(float[] a, float[] b)
    {
        double dotProduct = 0;
        double magA = 0;
        double magB = 0;

        for(int i = 0;i<a.length;i++)
        {
            dotProduct+=a[i]*b[i];
            magA+=a[i]*a[i];
            magB+=b[i]*b[i];
        }
        return dotProduct/(Math.sqrt(magA)*Math.sqrt(magB));
    }

}
