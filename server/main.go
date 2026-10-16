// Qolab Go backend (greenfield, option B).
//
// Runs a plain net/http server. Routes:
//
//	GET /health  → 200 {"status":"ok"}
//
// The mux is created by NewRouter so tests can exercise routes with
// httptest without starting real listeners.
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
)

// NewRouter builds the application's HTTP mux.
func NewRouter() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", handleHealth)
	return mux
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func main() {
	addr := os.Getenv("ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("qolab-server listening on %s", addr)
	if err := http.ListenAndServe(addr, NewRouter()); err != nil {
		log.Fatal(err)
	}
}
