package hello

import "testing"

func TestGreetDefault(t *testing.T) {
	if got := Greet(""); got != "Hello, world!" {
		t.Errorf("Greet(\"\") = %q, want %q", got, "Hello, world!")
	}
}

func TestGreetNamed(t *testing.T) {
	if got := Greet("Pantera"); got != "Hello, Pantera!" {
		t.Errorf("Greet(\"Pantera\") = %q, want %q", got, "Hello, Pantera!")
	}
}
