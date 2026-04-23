package com.myapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class BusinessService {

    private final Random random = new Random();

    public double doComplexComputation(int iterations) {
        double result = 0;
        for (int i = 0; i < iterations; i++) {
            result += Math.sin(i) * Math.cos(i) * Math.tan(i);
            if (i % 1000 == 0) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        return BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
   //large dataset
    public double processLargeDataset(int size) {
        List<Double> data = new ArrayList<>();
        for (int i = 0; i < size; i++) data.add(random.nextDouble() * 1000);
        double sum = data.stream().mapToDouble(Double::doubleValue).sum();
        double average = sum / size;
        data.sort(Double::compareTo);
        double median = data.get(size / 2);
        log.debug("Processed {} records. Avg: {}, Median: {}", size, average, median);
        return average;
    }
}