package com.supplychainai.riskai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArticlesDTO {
    String articleId;
    String articleText;
    float[] articleEmbeddings;

}
