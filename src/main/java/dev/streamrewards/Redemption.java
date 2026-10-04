package dev.streamrewards;

/** Одна покупка награды зрителем. */
public record Redemption(String id, String rewardId, String rewardTitle, int cost,
                         String userName, String userInput) {
}
