.PHONY: build test lint clean run

BINARY := termx
VERSION ?= dev

build:
	go build -ldflags "-X main.version=$(VERSION)" -o dist/$(BINARY) ./

test:
	go test ./...

lint:
	@which golangci-lint >/dev/null 2>&1 || go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest
	golangci-lint run ./...

clean:
	rm -rf dist/

run: build
	./dist/$(BINARY) --version
