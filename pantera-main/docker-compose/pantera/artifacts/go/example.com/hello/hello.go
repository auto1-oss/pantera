// Package hello is a tiny test fixture used by publish-module.sh to verify
// Pantera's Go publish + group-resolve flow end-to-end.
package hello

// Greet returns a friendly greeting for the given name.
func Greet(name string) string {
	if name == "" {
		name = "world"
	}
	return "Hello, " + name + "!"
}
