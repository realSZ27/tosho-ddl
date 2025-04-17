# AnimeTosho DDL Proxy
Gives Sonarr a valid Torznab feed to pick releases from, and picks up which one it chose via Torrent Blackhole. Then, downloads the release with JDownloader.

# Setup
The easiest way to set this up is through Docker, although you can also use the jars provided in the releases tab.

1. Make sure you have [Docker](https://docs.docker.com/engine/install) and [Docker Compose](https://docs.docker.com/compose/install/#scenario-two-install-the-compose-plugin).
2. Make a `docker-compose.yaml` file with these contents:
~~~yaml
services:
  toshoddl:
    container_name: toshoddl-docker
    image: ghcr.io/realsz27/tosho-ddl:latest
    environment:
      - JDOWNLOADER_API_URL: http://jdownloader-2:3128/
      - BASE_URL: http://jdownloader-2:8080/
    volumes:
      - ./config/downloads:/downloads
      - ./config/blackhole:/blackhole
        
  # Thanks jlesage for making this container
  jdownloader-2:
    container_name: jdownloader-docker
    image: jlesage/jdownloader-2
    ports:
      - 5800:5800
    volumes:
      - ./config/jdownloader:/config:rw
      - ./config/downloads:/output:rw
~~~
### Environment Variables
If you are using the raw .jar, the environment variables can be passed through the command line by changing them from `THIS_FORM=` to `--this.form=`.

Most of these don't need to be changed.

| Variable            | Description                                                  | Default                |
|---------------------|--------------------------------------------------------------|------------------------|
| JDOWNLOADER_API_URL | The URL that the app will use to contact JDownloader.        | http://localhost:3128/ |
| BASE_URL            | The URL that the all will serve the fake .torrent file from. | http://localhost:8080/ |
| DOWNLOAD_FOLDER     | The folder where JDownloader will save the files to.         | /downloads             |
| BLACKHOLE_FOLDER    | The folder the app will look for .torrent files in.          | /blackhole             |
| SERVER_PORT         | The port the server will run on.                             | 8080                   |

## Setup Sonarr
1. In the `Download Clients` settings page, add a new `Torrent Blackhole` and set the `Torrent Folder` and `Watch Folder` to your blackhole and downloads folders respectively. Make sure Sonarr can access these folders, of course.
![blackhole-img.png](images/blackhole-img.png)
2. Next, in the `Indexers` settings page, add a new `Torznab` indexer and set the URL to `http://toshoddl:8080` and select everything available in `Categories` and `Anime Categories`.
![torznab-1.png](images/torznab-1.png)
![torznab-2.png](images/torznab-2.png)
Just to be sure, you can also set `Download Client` to the Torrent Blackhole with advanced settings turned on.
![advanced-download-client.png](images/advanced-download-client.png)
- Make sure to test both of these before moving on.

## Setup JDownloader
*Note: You don't have to use the docker container I provided as an example. Any instance will work.*

In `Advanced Settings`, turn on the `Deprecated Api` and restart JDownloader.
![jdownloader.png](images/jdownloader.png)

