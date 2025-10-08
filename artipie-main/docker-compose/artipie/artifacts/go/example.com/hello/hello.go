package hello

import "fmt"

// Greet returns a greeting message
func Greet(name string) string {
	return fmt.Sprintf("Hello, %s!", name)
}

// Version returns the module version
func Version() string {
	return "v1.0.0"
}
