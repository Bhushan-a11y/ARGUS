package com.supplychainai.riskai;

public class DSU {
    int parent[];

    DSU(int n)
    {
        parent = new int[n];
        for(int i=0;i<n;i++)
        {
            parent[i] = i;
        }
    }

    int find(int x)
    {
        while (parent[x]!=x) {
            x = parent[x];
        }
        return x;
    }

    void union(int i, int j)
    {
        int rootI = find(i);
        int rootJ = find(j);
        if(rootI!=rootJ)
            parent[rootJ] = rootI;
    }
}
