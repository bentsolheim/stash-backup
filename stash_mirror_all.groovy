#!/usr/bin/env groovy


import groovy.json.JsonSlurper

@Grab('commons-io:commons-io:2.4')
import org.apache.commons.io.IOUtils

@Grab('org.apache.httpcomponents:httpclient:4.3.1')
@Grab('org.apache.httpcomponents:httpclient-cache:4.3.1')
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.ResponseHandler
import org.apache.http.client.cache.CacheResponseStatus
import org.apache.http.client.cache.HttpCacheContext
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.cache.CacheConfig
import org.apache.http.impl.client.cache.CachingHttpClients


def main() {

    def log = { message, level = 0 ->
        level.times { print "  " }
        println message
    }

    def configFile = new File(".stash_backup")
    Properties p = new Properties()
    p.load(new FileInputStream(configFile))

    def basePath = new File(p["backupPath"])
    log "Backing up to $basePath.absolutePath"

    def stashUrl = p["stashUrl"]
    def baseUrl = stashUrl + "/rest/api/1.0"

    def username = p["username"]
    def password = p["password"]
    def headers = ['Authorization': "Basic " + "$username:$password".bytes.encodeBase64().toString()]
    def sshCloneUrl = p["sshCloneUrl"]

    def cloneUrlBasePath = stashUrl.replace("://", "://$username@") + "/scm"

    CloseableHttpClient httpClient = HttpComponentUtils.createCloseableCachingHttpCache()

    def projects = HttpComponentUtils.getJson(httpClient, "$baseUrl/projects/", headers)
    log "Found ${projects.values.size()} projects"

    projects.values.each { project ->

        log "Looking for repositories for project $project.name ($project.key)"
        def repos = HttpComponentUtils.getJson(httpClient, "$baseUrl/$project.link.url/repos", headers)
        log "Found ${repos.values.size} repositories"

        repos.values.each { repo ->
            log "Backing up repository $repo.name", 1

            def repoPath = repo.cloneUrl.replace(cloneUrlBasePath, "")
            def cloneUrl = sshCloneUrl + repoPath

            def checkoutPathFile = new File(basePath, repoPath)
            if (checkoutPathFile.exists()) {
                log "$repo.name already mirrored. Updating from remote.", 2
                def cmd = "cd $checkoutPathFile.absolutePath; git remote update;"
                log cmd, 2
                exec(cmd)
            } else {
                log "$repo.name is new. Making a clone --mirror", 2
                def cmd = "git clone --mirror $cloneUrl $checkoutPathFile.absolutePath"
                log cmd, 2
                exec(cmd)
            }
        }
        println "\n"
    }
}

main()


class HttpComponentUtils {

    static CloseableHttpClient createCloseableCachingHttpCache(CredentialsProvider credsProvider = null) {

        CacheConfig config = CacheConfig.custom().with {
            maxCacheEntries = 1000

            // This needs to be higher than any conceivable response from the server,
            // or else the inputStream will be closed while reading
            maxObjectSize = 100 * 1024 * 1024

            // Make sure authenticated responses are also cached
            sharedCache = false
            build()
        }
        RequestConfig requestConfig = RequestConfig.custom().with {
            // The maximum period inactivity between two consecutive data packets.
            socketTimeout = 30000

            // Determines the timeout in milliseconds until a connection is established.
            connectTimeout = 30000

            // Returns the timeout in milliseconds used when requesting a connection from
            // the connection manager. It is extremely important that this is set to make sure clients
            // are not allowed to wait forever for a connection.
            connectionRequestTimeout = 30000
            build()
        }

        CloseableHttpClient httpClient = CachingHttpClients.custom().with {
            cacheConfig = config
            defaultRequestConfig = requestConfig
            if (credsProvider) {
                defaultCredentialsProvider = credsProvider
            }
            build()
        }
        httpClient
    }


    static BasicCredentialsProvider createBasicCredentialsProvider(Map hostCredentials) {

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider()
        hostCredentials.each { String hostAndPort, Map<String, String> credentials ->
            def (host, port) = hostAndPort.split(':')
            port = port as Integer
            credentialsProvider.setCredentials(
                    new AuthScope(host, port),
                    new UsernamePasswordCredentials(credentials.userName, credentials.password)
            )
        }
        credentialsProvider
    }


    static Object getJson(CloseableHttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

        new JsonSlurper().parseText(getString(httpClient, url, headers))
    }


    static String getString(CloseableHttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

        ByteArrayOutputStream bytes = getBytes(httpClient, url, headers)
        bytes.toString()
    }


    static ByteArrayOutputStream getBytes(CloseableHttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

        HttpGet getRequest = new HttpGet(url)
        headers.each { key, value ->
            getRequest.addHeader(key, value)
        }
        HttpCacheContext context = HttpCacheContext.create()

        httpClient.execute(getRequest, { HttpResponse response ->

            HttpEntity entity = response.entity

            def content = new ByteArrayOutputStream()
            entity.writeTo(content)

            content
        } as ResponseHandler<ByteArrayOutputStream>, context)
    }
}


def exec(String cmd) {

    runCmd(cmd, System.out)
}


protected void runCmd(String cmd, OutputStream out) {

    String[] sh = [
            "/bin/sh",
            "-c",
            cmd
    ]

    def process = Runtime.runtime.exec(sh)
    IOUtils.copy(new InputStreamReader(process.inputStream), out)
    process.waitFor()
}
