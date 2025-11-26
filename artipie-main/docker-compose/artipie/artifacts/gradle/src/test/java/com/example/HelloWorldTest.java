package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HelloWorld.
 */
public class HelloWorldTest {
    
    @Test
    public void testCreateMessage() {
        String result = HelloWorld.createMessage("Hello", "World");
        assertEquals("Hello World", result);
    }
    
    @Test
    public void testGreetWithName() {
        String result = HelloWorld.greet("artipie");
        assertEquals("Hello, Artipie!", result);
    }
    
    @Test
    public void testGreetWithBlankName() {
        String result = HelloWorld.greet("");
        assertEquals("Hello, World!", result);
    }
    
    @Test
    public void testGreetWithNull() {
        String result = HelloWorld.greet(null);
        assertEquals("Hello, World!", result);
    }
}
