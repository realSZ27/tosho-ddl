# Disclaimers
- I'm still learning! If you have any CONSTRUCTIVE feedback, please say something!
- Although I intend to maintain this app as long as I can, I have other things to keep up with.
- For now, I'm calling this a beta. It works fine for me, but there are probably dozens of bugs to fix before I can call it fully functional.

# DDL Proxy
Despite the name, this supports more than just AnimeTosho. 
Gives Sonarr a valid Torznab feed to pick releases from, and picks up which one it chose via Torrent Blackhole. Then, downloads the release with JDownloader.

## Supported Sites
Currently the app supports:
| Site Name    | Comments                                                                                                                                                                                                                                                                                                                                                                          |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AnimeTosho   | Original site. Supports all features out of the box. Will shut down permanantly May 9th.                                                                                                                                                                                                                                                                                          |
| TokyoInsider | Frequently goes down, and is user-submitted. However, it has many releases from good groups, and requires no setup.                                                                                                                                                                                                                                                               |
| KayoAnime    | Requires setting up [Google Drive](https://support.jdownloader.org/en/knowledgebase/article/account-cookie-login-instructions) with JDownloader. Large library of mini encodes. Private folders are hit or miss. JDownloader says they don't work at all, but I've found some that do, so it's worth joining their group anyway (it's the same group for everything on the site). |

If you have a request, or want to add more yourself, open an issue or pr.

## Limitations
- Links expire quickly on AnimeTosho, so pretty much only new releases work. Unfortunately, there's nothing I can do about this.
- Links aren't checked for availability until after they are chosen. This means that sonarr might choose a release that can't be downloaded.

# Setup
The easiest way to set this up is through Docker, although you can also use the jars provided in the releases tab.

1. Make sure you have [Docker](https://docs.docker.com/engine/install) and [Docker Compose](https://docs.docker.com/compose/install/).
2. Make a `compose.yaml` file with these contents:

> [!IMPORTANT]  
> This assumes your Sonarr instance is in the same compose file, or at least on the same docker network. If you want, you can always add 8080 as a port on the container and use normal networking.

> [!NOTE]  
> You don't have to use this container, or any container for that matter. Any JDownloader instance will work. Just make sure you have properly set `JDOWNLOADER_API_URL` and `BASE_URL` so that JDownloader can contact the app, and vice versa.

~~~yaml
services:
  tosho-ddl:
    container_name: tosho-ddl
    image: ghcr.io/realsz27/tosho-ddl:latest # Docker Hub mirrors are also available at sz27/tosho-ddl
    restart: unless-stopped
    environment:
      - JDOWNLOADER_API_URL=http://jdownloader-2:3128/
      - BASE_URL=http://tosho-ddl:8080/ # This is needed because sonarr needs somewhere do download the torrent from.
    volumes:
      - ./config/downloads:/downloads
      - ./config/blackhole:/blackhole
  
  jdownloader-2:
    container_name: jdownloader-docker
    image: jlesage/jdownloader-2
    restart: unless-stopped
    ports:
      - 5800:5800
    volumes:
      - ./config/jdownloader:/config:rw
      - ./config/downloads:/output:rw
~~~

If you would like to disable a source, you can add an evironment variable like: `APP_SOURCES_{SOURCE NAME}_ENABLED=false`. 

### Environment Variables

> [!IMPORTANT]  
> If you are using the raw .jar, the environment variables can be passed through the command line by changing them from `THIS_FORM=` to `--this.form=`.

Most of these don't need to be changed.

| Variable                          | Description                                                                                                                                                           | Default                |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| JDOWNLOADER_API_URL               | URL used to communicate with the JDownloader API.                                                                                                                     | http://localhost:3128/ |
| BASE_URL                          | Base URL where generated .torrent files are served.                                                                                                                   | http://localhost:8080/ |
| DOWNLOAD_FOLDER                   | Directory where JDownloader saves downloaded files                                                                                                                    | /downloads             |
| BLACKHOLE_FOLDER                  | Directory monitored for incoming .torrent files                                                                                                                       | /blackhole             |
| SERVER_PORT                       | The port the server will run on.                                                                                                                                      | 8080                   |
| LOGGING_LEVEL_DEV_DDLPROXY        | Logging level for the application (Spring Boot logging config).                                                                                                       | INFO                   |
| APP_SOURCES_{SOURCE NAME}_ENABLED | Set the enabled status for a particular source. Replace {SOURCE NAME} with the name of the source. The names are the same as [the list at the top](#supported-sites). | true (on all)          |
| APP_CLEANUPENABLED                | Enables automatic deletion of old files in the download folder.                                                                                                       | true                   |

## Setup Sonarr

### 1. Add Torrent Blackhole

> [!NOTE]
> The torrent file that this app serves isn't real. It has totally fake files and trackers, sonarr just requires a fully formed file for some reason.

In the `Download Clients` settings page, add a new `Torrent Blackhole` and set the `Torrent Folder` and `Watch Folder` to your blackhole and downloads folders respectively. 

> [!IMPORTANT]
> Make sure the `Torrent Folder` and `Watch Folder` in Sonarr point to the appropriate directories where the app and JDownloader can interact. This is `./config/blackhole` and `./config/downloads` in the example config respectively. Remember to mount these to your sonarr container.

![blackhole-img.png](images/blackhole-img.png)

### 2. Add Torznab Indexer

2. Next, in the `Indexers` settings page, add a new `Torznab` indexer and set the URL to `http://tosho-ddl:8080` and select everything available in `Categories` and `Anime Categories`.

![torznab-1.png](images/torznab-1.png)
![torznab-2.png](images/torznab-2.png)

Just to be sure, you can also set `Download Client` to the Torrent Blackhole with advanced settings turned on.

![advanced-download-client.png](images/advanced-download-client.png)

> [!TIP]
> Make sure to test both of these before moving on.

## Setup JDownloader

> [!CAUTION]
> You don't need to use the provided Docker container for JDownloader. Any instance of JDownloader will work as long as it's configured right. **However**, it is recommended that you have a dedicated JDownloader instance for this app. This is because the container clears the LinkGrabber queue when checking if links are online. This might be a problem if you're using JDownloader for other tasks, especially if you have many shows airing on the same day.

In `Advanced Settings`, turn on `Deprecated Api`, off `Deprecated Api Localhost Only` and restart JDownloader.

![jdownloader.png](images/jdownloader.png)
