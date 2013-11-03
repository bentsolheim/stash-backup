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
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.cache.CacheConfig
import org.apache.http.impl.client.cache.CachingHttpClients


class StashMirrorAll {

    File backupPath
    String apiBaseUrl
    String sshBaseUrl
    String sshCloneUrl
    String cloneUrlBasePath

    Map<String, String> headers = [:]

    HttpClient httpClient

    StashMirrorAll(Properties p) {

        String stashUrl = p["stashUrl"]
        String username = p["username"]
        String password = p["password"]

        this.with {
            backupPath = new File(p["backupPath"])
            apiBaseUrl = stashUrl + "/rest/api/1.0"
            sshBaseUrl = stashUrl + "/rest/ssh/1.0"
            cloneUrlBasePath = stashUrl.replace("://", "://$username@") + "/scm"

            headers += ['Authorization': "Basic " + "$username:$password".bytes.encodeBase64().toString()]
            sshCloneUrl = p["sshCloneUrl"]
            httpClient = HttpComponentUtils.createCloseableCachingHttpCache()
        }
    }


    void mirrorAllRepositories() {

        log "Backing up to $backupPath.absolutePath"

        mirrorRepositoriesFromProjects()
        mirrorRepositoriesFromUsers()
//        downloadUserKeys()
    }


    void mirrorRepositoriesFromProjects() {

        log "\n\n-- MIRRORING PROJECT REPOSITORIES --"

        def projects = HttpComponentUtils.getJson(httpClient, "$apiBaseUrl/projects/", headers)
        log "Found ${projects.values.size()} projects"

        projects.values.each { project ->

            log "Looking for repositories for project $project.name ($project.key)"
            def repos = HttpComponentUtils.getJson(httpClient, "$apiBaseUrl/$project.link.url/repos", headers)
            log "Found ${repos.values.size} repositories"

            repos.values.each { repo ->
                mirrorRepository(repo)
            }
            log "\n"
        }
    }


    void mirrorRepositoriesFromUsers() {

        log "\n\n-- MIRRORING USER REPOSITORIES --"

        def users = HttpComponentUtils.getJson(httpClient, "$apiBaseUrl/users/", headers)
        log "Found ${users.values.size()} users"

        users.values.each { user ->

            log "Looking for repositories for user $user.name ($user.slug)"
            def repos = HttpComponentUtils.getJson(httpClient, "$apiBaseUrl/users/$user.slug/repos")
            log "Found ${repos.values.size} repositories"

            repos.values.each { repo ->
                mirrorRepository(repo)
            }
            log "\n"
        }
    }


    void mirrorRepository(Map repository) {

        log "Backing up repository $repository.name", 1

        def repoPath = repository.cloneUrl.replace(cloneUrlBasePath, "")
        def cloneUrl = sshCloneUrl + repoPath

        def checkoutPathFile = new File(backupPath, repoPath)
        if (checkoutPathFile.exists()) {
            log "$repository.name already mirrored. Updating from remote.", 2
            def cmd = "cd $checkoutPathFile.absolutePath; git remote update;"
            log cmd, 2
            exec(cmd)
        } else {
            log "$repository.name is new. Making a clone --mirror", 2
            def cmd = "git clone --mirror $cloneUrl $checkoutPathFile.absolutePath"
            log cmd, 2
            exec(cmd)
        }
    }


    void downloadUserKeys() {

        def users = HttpComponentUtils.getJson(httpClient, "$apiBaseUrl/users/", headers)
        users.values.each { user ->

            println user
            println HttpComponentUtils.getJson(httpClient, "$sshBaseUrl/keys?user=$user.name", headers)
        }
    }


    static void log(message, level = 0) {

        level.times { print "  " }
        println message
    }


    protected static void exec(String cmd) {

        runCmd(cmd, System.out)
    }


    protected static void runCmd(String cmd, OutputStream out) {

        String[] sh = [
                "/bin/sh",
                "-c",
                cmd
        ]

        def process = Runtime.runtime.exec(sh)
        IOUtils.copy(new InputStreamReader(process.inputStream), out)
        process.waitFor()
    }

}


def main() {

    def configFile = new File(".stash_backup")
    Properties p = new Properties()
    p.load(new FileInputStream(configFile))

    new StashMirrorAll(p).mirrorAllRepositories()
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


    static Object getJson(HttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

        new JsonSlurper().parseText(getString(httpClient, url, headers))
    }


    static String getString(HttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

        ByteArrayOutputStream bytes = getBytes(httpClient, url, headers)
        bytes.toString()
    }


    static ByteArrayOutputStream getBytes(HttpClient httpClient, String url, Map headers = [:]) throws ClientProtocolException, IOException {

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
