package hudson.plugins.mercurial;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.model.AbstractModelObject;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.RootAction;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.NotSerilizableSecurityContext;
import hudson.triggers.SCMTrigger;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.servlet.http.HttpServletResponse.*;
import org.springframework.security.Authentication;
import org.springframework.security.context.SecurityContext;
import org.springframework.security.context.SecurityContextHolder;
/**
 * Information screen for the use of Mercurial in Hudson.
 */
@Extension
public class MercurialStatus extends AbstractModelObject implements RootAction {
    public String getDisplayName() {
        return Messages.MercurialStatus_mercurial();
    }

    public String getSearchUrl() {
        return getUrlName();
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return "mercurial";
    }
    
    private static int getPort(URI uri) {
        int port = uri.getPort();
        if ( port < 0 ){
            String scheme = uri.getScheme();
            if ( scheme.equals("http") ){
                port = 80;
            } else if ( scheme.equals("https") ) {
                port = 443;
            } else if ( scheme.equals("ssh") ) {
                port = 22;
            }
        }
        return port;
    }
    
    static boolean looselyMatches(URI notifyUri, String repository) {
        boolean result = false;
        try {
            URI repositoryUri = new URI(repository);
            result = Objects.equal(notifyUri.getScheme(), repositoryUri.getScheme()) 
                && Objects.equal(notifyUri.getHost(), repositoryUri.getHost()) 
                && getPort(notifyUri) == getPort(repositoryUri)
                && Objects.equal(notifyUri.getPath(), repositoryUri.getPath())
                && Objects.equal(notifyUri.getQuery(), repositoryUri.getQuery());
        } catch ( URISyntaxException ex ) {
            LOGGER.log(Level.SEVERE, "could not parse repository uri " + repository, ex);
        }
        return result;
    }

    public HttpResponse doNotifyCommit(@QueryParameter(required=true) final String url) throws ServletException, IOException {
        // run in high privilege to see all the projects anonymous users don't see.
        // this is safe because we only initiate polling.
        //SecurityContext securityContext = ACL.impersonate(ACL.SYSTEM);
        try {
            return handleNotifyCommit(new URI(url));
        } catch ( URISyntaxException ex ) {
            throw HttpResponses.error(SC_BAD_REQUEST, ex);
        } finally {
            //SecurityContextHolder.setContext(securityContext);
        }
    }
    
    private HttpResponse handleNotifyCommit(URI url) throws ServletException, IOException {
        final List<AbstractProject<?,?>> projects = Lists.newArrayList();
        boolean scmFound = false,
                triggerFound = false,
                urlFound = false;
        for (AbstractProject<?,?> project : Hudson.getInstance().getAllItems(AbstractProject.class)) {
            SCM scm = project.getScm();
            if (scm instanceof MercurialSCM) scmFound = true; else continue;

            MercurialSCM hg = (MercurialSCM) scm;
            String repository = hg.getSource();
            if (repository == null) {
                LOGGER.log(Level.FINE, "project " + project.getDisplayName() + " is using source control but does not identify a repository");
                continue;
            }
            LOGGER.log(Level.INFO, "url == " + url + " repository == " + repository);
            if (looselyMatches(url, repository)) urlFound = true; else continue;
            SCMTrigger trigger = project.getTrigger(SCMTrigger.class);
            if (trigger!=null) triggerFound = true; else continue;

            LOGGER.log(Level.INFO, "Triggering the polling of {0}", project.getFullDisplayName());
            trigger.run();
            projects.add(project);
        }

        final String msg;
        if (!scmFound)  msg = "No mercurial jobs found";
        else if (!urlFound) msg = "No mercurial jobs using repository: " + url;
        else if (!triggerFound) msg = "Jobs found but they aren't configured for polling";
        else msg = null;

        return new HttpResponse() {
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
                rsp.setStatus(SC_OK);
                rsp.setContentType("text/plain");
                for (AbstractProject<?, ?> p : projects) {
                    rsp.addHeader("Triggered", p.getAbsoluteUrl());
                }
                PrintWriter w = rsp.getWriter();
                for (AbstractProject<?, ?> p : projects) {
                    w.println("Scheduled polling of "+p.getFullDisplayName());
                }
                if (msg!=null)
                    w.println(msg);
            }
        };
    }

    private static final Logger LOGGER = Logger.getLogger(MercurialStatus.class.getName());
}
