package hello

import "testing"

func TestGreet(t *testing.T) {
	result := Greet("World")
	expected := "Hello, World!"
	if result != expected {
		t.Errorf("Greet(\"World\") = %s; want %s", result, expected)
	}
}

func TestVersion(t *testing.T) {
	result := Version()
	if result == "" {
		t.Error("Version() returned empty string")
	}
}
