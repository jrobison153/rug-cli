package com.atomist.rug.cli.command;

import static scala.collection.JavaConversions.asJavaCollection;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.springframework.util.StringUtils;

import com.atomist.event.SystemEvent;
import com.atomist.plan.TreeMaterializer;
import com.atomist.project.archive.Operations;
import com.atomist.rug.BadRugException;
import com.atomist.rug.RugRuntimeException;
import com.atomist.rug.cli.command.utils.ArtifactSourceUtils;
import com.atomist.rug.cli.output.ProgressReportingOperationRunner;
import com.atomist.rug.cli.settings.SettingsReader;
import com.atomist.rug.cli.utils.ArtifactDescriptorUtils;
import com.atomist.rug.compiler.Compiler;
import com.atomist.rug.compiler.ServiceLoaderCompilerRegistry;
import com.atomist.rug.kind.service.ConsoleMessageBuilder;
import com.atomist.rug.loader.DecoratingOperationsLoader;
import com.atomist.rug.loader.HandlerOperationsLoader;
import com.atomist.rug.loader.Handlers;
import com.atomist.rug.loader.OperationsAndHandlers;
import com.atomist.rug.loader.OperationsLoaderException;
import com.atomist.rug.resolver.ArtifactDescriptor;
import com.atomist.rug.resolver.ArtifactDescriptor.Extension;
import com.atomist.rug.resolver.LocalArtifactDescriptor;
import com.atomist.rug.resolver.UriBasedDependencyResolver;
import com.atomist.source.ArtifactSource;
import com.atomist.source.Deltas;
import com.atomist.tree.TreeNode;
import com.atomist.tree.pathexpression.PathExpression;

import scala.collection.JavaConverters;

public abstract class AbstractCompilingAndOperationLoadingCommand extends AbstractCommand {
    
    @Override
    protected final void run(URI[] uri, ArtifactDescriptor artifact, CommandLine commandLine) {
        OperationsAndHandlers operationsAndHandlers = null;
        if (artifact != null && artifact.extension() == Extension.ZIP) {

            ArtifactSource source = ArtifactSourceUtils.createArtifactSource(artifact);
            source = compile(source);
            operationsAndHandlers = loadOperationsAndHandlers(artifact, source,
                    createOperationsLoader(uri));
        }
        run(operationsAndHandlers, artifact, commandLine);
    }
    
    private ArtifactSource compile(ArtifactSource source) {
        // Get all registered and supported compilers
        Collection<Compiler> compilers = asJavaCollection(
                ServiceLoaderCompilerRegistry.findAll(source));

        if (!compilers.isEmpty()) {

            return new ProgressReportingOperationRunner<ArtifactSource>("Processing script sources")
                    .run(indicator -> {
                        ArtifactSource compiledSource = source;
                        for (Compiler compiler : compilers) {
                            indicator.report(String.format("Invoking %s on %s script sources",
                                    compiler.name(),
                                    StringUtils.collectionToCommaDelimitedString(JavaConverters
                                            .asJavaCollectionConverter(compiler.extensions())
                                            .asJavaCollection())));
                            ArtifactSource cs = compiler.compile(compiledSource);
                            Deltas deltas = cs.deltaFrom(compiledSource);
                            if (deltas.empty()) {
                                indicator.report("  No files modified");
                            }
                            else {
                                asJavaCollection(deltas.deltas())
                                        .forEach(d -> indicator.report("  Created " + d.path()));
                            }
                            compiledSource = cs;
                        }
                        return compiledSource;
                    });
        }
        else {
            return source;
        }
    }
    
    private HandlerOperationsLoader createOperationsLoader(URI[] uri) {
        HandlerOperationsLoader loader = new DecoratingOperationsLoader(
                new UriBasedDependencyResolver(uri,
                        new SettingsReader().read().getLocalRepository().path())) {
            @Override
            protected List<ArtifactDescriptor> postProcessArfifactDescriptors(
                    ArtifactDescriptor artifact, List<ArtifactDescriptor> dependencies) {
                if (artifact instanceof LocalArtifactDescriptor) {
                    dependencies.add(artifact);
                }
                return dependencies;
            }
        };
        return loader;
    }

    private OperationsAndHandlers doLoadOperationsAndHandlers(ArtifactDescriptor artifact,
            ArtifactSource source, HandlerOperationsLoader loader) throws Exception {
        try {
            Operations operations = loader.load(artifact, source);
            Handlers handlers = loader.loadHandlers("", artifact, source,
                    new ConsoleMessageBuilder("", null), new TreeMaterializer() {
                        @Override
                        public TreeNode rootNodeFor(SystemEvent systemEvent,
                                PathExpression pathExpression) {
                            return null;
                        }
                    });

            return new OperationsAndHandlers(operations, handlers);
        }
        catch (Exception e) {
            if (e instanceof BadRugException) {
                throw new CommandException("Failed to load archive: \n" + e.getMessage(), e);
            }
            else if (e instanceof OperationsLoaderException) {
                if (e.getCause() instanceof BadRugException) {
                    throw new CommandException(
                            "Failed to load archive: \n" + e.getCause().getMessage(), e);
                }
                else if (e.getCause() instanceof RugRuntimeException) {
                    throw new CommandException(
                            "Failed to load archive: \n" + e.getCause().getMessage(), e);
                }
            }
            throw e;
        }
    }

    private OperationsAndHandlers loadOperationsAndHandlers(ArtifactDescriptor artifact,
            ArtifactSource source, HandlerOperationsLoader loader) {
        if (artifact == null || source == null) {
            return null;
        }

        return new ProgressReportingOperationRunner<OperationsAndHandlers>(String
                .format("Loading %s into runtime", ArtifactDescriptorUtils.coordinates(artifact)))
                        .run(indicator -> {
                            return doLoadOperationsAndHandlers(artifact, source, loader);
                        });
    }


    protected abstract void run(OperationsAndHandlers operationsAndHandlers,
            ArtifactDescriptor artifact, CommandLine commandLine);

}
