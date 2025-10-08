package com.example;

import com.google.common.base.Joiner;
import org.apache.commons.lang3.StringUtils;

/**
 * Simple Hello World application demonstrating Artipie Gradle integration.
 */
public class HelloWorld {
    
    /**
     * Main method.
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        String message = createMessage("Hello", "from", "Artipie", "Gradle", "Repository");
        System.out.println(message);
        System.out.println(StringUtils.repeat("=", message.length()));
    }
    
    /**
     * Create a message by joining words.
     * @param words Words to join
     * @return Joined message
     */
    public static String createMessage(String... words) {
        return Joiner.on(" ").join(words);
    }
    
    /**
     * Get greeting message.
     * @param name Name to greet
     * @return Greeting message
     */
    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        }
        return String.format("Hello, %s!", StringUtils.capitalize(name));
    }
}
