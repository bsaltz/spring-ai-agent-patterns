# Getting Started

## Installing Tools

You will need to install:

* [Java 21](https://adoptium.net/temurin/releases/?version=21&os=any&arch=any)
* [Ollama](https://ollama.com/download)
* [ocrmypdf](https://ocrmypdf.readthedocs.io/en/latest/installation.html)
* pdftotext (part of [Poppler](https://poppler.freedesktop.org/))
* [uv/uvx](https://docs.astral.sh/uv/getting-started/installation/)
* npm/npx (e.g. via [nvm](https://github.com/nvm-sh/nvm or [nvm-windows](https://github.com/coreybutler/nvm-windows))

Optionally, you can install [mkdocs](https://www.mkdocs.org/user-guide/installation/) to build and view the
documentation locally rather than using a code editor or GitHub.

## Configuration

### Configuring Ollama

Ollama models need to be pulled before running the project:

```shell
ollama pull gpt-oss:20b
```

The project will not pull the model automatically.

### Configuring a Brave API Key

Some examples in the project require a Brave API key. You can get one from the [Brave Developer
Portal](https://developers.brave.com/docs/api/authentication/). Place the key in
`src/main/resources/application-local.yml`, e.g.:

```yaml
com.brave.search.apiKey: YOUR_API_KEY
```

## Building the Project

This project uses Gradle to build. You can run linting and tests from the project root with:

```shell
./gradlew check
```

## Running the Project

Make sure that Docker and Ollama are ready:

```shell
ollama -v
```

## Running the Application

The Gradle wrapper can be used to run the application:

```shell
./gradlew bootRun
```

## Viewing the Documentation

The documentation can be viewed locally with:

```shell
mkdocs serve
```

The documentation will be available at http://localhost:8000/.
