/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetaData;
import org.gradle.internal.component.model.DependencyMetaData;
import org.gradle.internal.resolve.ModuleVersionNotFoundException;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.result.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult.State.Failed;
import static org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult.State.Resolved;

public class DynamicVersionResolver implements DependencyToComponentIdResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicVersionResolver.class);

    private final List<ModuleComponentRepository> repositories = new ArrayList<ModuleComponentRepository>();
    private final List<String> repositoryNames = new ArrayList<String>();
    private final VersionedComponentChooser versionedComponentChooser;
    private final Transformer<ModuleComponentResolveMetaData, RepositoryChainModuleResolution> metaDataFactory;

    public DynamicVersionResolver(VersionedComponentChooser versionedComponentChooser, Transformer<ModuleComponentResolveMetaData, RepositoryChainModuleResolution> metaDataFactory) {
        this.versionedComponentChooser = versionedComponentChooser;
        this.metaDataFactory = metaDataFactory;
    }

    public void add(ModuleComponentRepository repository) {
        repositories.add(repository);
        repositoryNames.add(repository.getName());
    }

    public void resolve(DependencyMetaData dependency, BuildableComponentIdResolveResult result) {
        ModuleVersionSelector requested = dependency.getRequested();
        LOGGER.debug("Attempting to resolve {} using repositories {}", requested, repositoryNames);
        List<Throwable> errors = new ArrayList<Throwable>();

        List<RepositoryResolveState> resolveStates = new ArrayList<RepositoryResolveState>();
        for (ModuleComponentRepository repository : repositories) {
            resolveStates.add(new RepositoryResolveState(dependency, repository));
        }

        final RepositoryChainModuleResolution latestResolved = findLatestModule(resolveStates, errors);
        if (latestResolved != null) {
            LOGGER.debug("Using {} from {}", latestResolved.module.getId(), latestResolved.repository);
            for (Throwable error : errors) {
                LOGGER.debug("Discarding resolve failure.", error);
            }

            result.resolved(metaDataFactory.transform(latestResolved));
            return;
        }
        if (!errors.isEmpty()) {
            result.failed(new ModuleVersionResolveException(requested, errors));
        } else {
            notFound(result, requested, resolveStates);
        }
    }

    private void notFound(BuildableComponentIdResolveResult result, ModuleVersionSelector requested, List<RepositoryResolveState> resolveStates) {
        Set<String> unmatchedVersions = new LinkedHashSet<String>();
        Set<String> rejectedVersions = new LinkedHashSet<String>();

        for (RepositoryResolveState resolveState : resolveStates) {
            resolveState.applyTo(result, unmatchedVersions, rejectedVersions);
        }
        result.failed(new ModuleVersionNotFoundException(requested, result.getAttempted(), unmatchedVersions, rejectedVersions));
    }

    private RepositoryChainModuleResolution findLatestModule(List<RepositoryResolveState> resolveStates, Collection<Throwable> failures) {
        LinkedList<RepositoryResolveState> queue = new LinkedList<RepositoryResolveState>();
        queue.addAll(resolveStates);

        LinkedList<RepositoryResolveState> missing = new LinkedList<RepositoryResolveState>();

        // A first pass to do local resolves only
        RepositoryChainModuleResolution best = findLatestModule(queue, failures, missing);
        if (best != null) {
            return best;
        }

        // Nothing found - do a second pass
        queue.addAll(missing);
        missing.clear();
        return findLatestModule(queue, failures, missing);
    }

    private RepositoryChainModuleResolution findLatestModule(LinkedList<RepositoryResolveState> queue, Collection<Throwable> failures, Collection<RepositoryResolveState> missing) {
        RepositoryChainModuleResolution best = null;
        while (!queue.isEmpty()) {
            RepositoryResolveState request = queue.removeFirst();
            try {
                request.resolve();
            } catch (Throwable t) {
                failures.add(t);
                continue;
            }
            switch (request.resolveResult.getState()) {
                case Failed:
                    failures.add(request.resolveResult.getFailure());
                    break;
                case Missing:
                case Unknown:
                    // Queue this up for checking again later
                    if (request.canMakeFurtherAttempts()) {
                        missing.add(request);
                    }
                    break;
                case Resolved:
                    RepositoryChainModuleResolution moduleResolution = new RepositoryChainModuleResolution(request.repository, request.resolveResult.getMetaData());
                    best = chooseBest(best, moduleResolution);
                    break;
                default:
                    throw new IllegalStateException("Unexpected state for resolution: " + request.resolveResult.getState());
            }
        }

        return best;
    }

    private RepositoryChainModuleResolution chooseBest(RepositoryChainModuleResolution one, RepositoryChainModuleResolution two) {
        if (one == null || two == null) {
            return two == null ? one : two;
        }
        return versionedComponentChooser.selectNewestComponent(one.module, two.module) == one.module ? one : two;
    }

    private class RepositoryResolveState {
        private final DefaultBuildableModuleComponentMetaDataResolveResult resolveResult = new DefaultBuildableModuleComponentMetaDataResolveResult();
        private final Map<String, CandidateResult> candidateComponents = new HashMap<String, CandidateResult>();
        private final VersionListResult versionListingResult;
        private final ModuleComponentRepository repository;
        private final DependencyMetaData dependency;
        private final DefaultBuildableComponentSelectionResult componentSelectionResult = new DefaultBuildableComponentSelectionResult();

        public RepositoryResolveState(DependencyMetaData dependency, ModuleComponentRepository repository) {
            this.dependency = dependency;
            this.repository = repository;
            versionListingResult = new VersionListResult(dependency, repository);
        }

        public boolean canMakeFurtherAttempts() {
            return versionListingResult.canMakeFurtherAttempts();
        }

        void resolve() {
            versionListingResult.resolve();
            switch (versionListingResult.result.getState()) {
                case Failed:
                    resolveResult.failed(versionListingResult.result.getFailure());
                    break;
                case Listed:
                    selectMatchingVersionAndResolve();
                    break;
                case Unknown:
                    break;
                default:
                    throw new IllegalStateException("Unexpected state for version list result.");
            }
        }

        private void selectMatchingVersionAndResolve() {
            // TODO - reuse metaData if it was already fetched to select the component from the version list
            versionedComponentChooser.selectNewestMatchingComponent(candidates(), dependency, componentSelectionResult);
            switch (componentSelectionResult.getState()) {
                // No version matching list: component is missing
                case NoMatch:
                    componentSelectionResult.applyTo(resolveResult);
                    resolveResult.missing();
                    break;
                // Found version matching in list: resolve component
                case Match:
                    ModuleComponentIdentifier selectedComponentId = componentSelectionResult.getMatch();
                    CandidateResult candidateResult = candidateComponents.get(selectedComponentId.getVersion());
                    candidateResult.resolve(resolveResult);
                    break;
                case Failed:
                    resolveResult.failed(componentSelectionResult.getFailure());
                    break;
                default:
                    throw new IllegalStateException("Unexpected state for component selection result.");
            }
        }

        private List<CandidateResult> candidates() {
            List<CandidateResult> candidates = new ArrayList<CandidateResult>();
            for (String version : versionListingResult.result.getVersions()) {
                CandidateResult candidateResult = candidateComponents.get(version);
                if (candidateResult == null) {
                    candidateResult = new CandidateResult(dependency, version, repository);
                    candidateComponents.put(version, candidateResult);
                }
                candidates.add(candidateResult);
            }
            return candidates;
        }

        protected void applyTo(ResourceAwareResolveResult target, Set<String> unmatchedVersions, Set<String> rejectedVersions) {
            versionListingResult.applyTo(target);
            resolveResult.applyTo(target);
            unmatchedVersions.addAll(componentSelectionResult.getUnmatchedVersions());
            rejectedVersions.addAll(componentSelectionResult.getRejectedVersions());
        }
    }

    private static class CandidateResult implements ModuleComponentResolveState {
        private final ModuleComponentIdentifier identifier;
        private final ModuleComponentRepository repository;
        private final DependencyMetaData dependencyMetaData;
        private final String version;
        private boolean searchedLocally;
        private boolean searchedRemotely;
        private final DefaultBuildableModuleComponentMetaDataResolveResult result = new DefaultBuildableModuleComponentMetaDataResolveResult();

        public CandidateResult(DependencyMetaData dependencyMetaData, String version, ModuleComponentRepository repository) {
            this.dependencyMetaData = dependencyMetaData;
            this.version = version;
            this.repository = repository;
            ModuleVersionSelector requested = dependencyMetaData.getRequested();
            this.identifier = DefaultModuleComponentIdentifier.newId(requested.getGroup(), requested.getName(), version);
        }

        @Override
        public ModuleComponentIdentifier getId() {
            return identifier;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public BuildableModuleComponentMetaDataResolveResult resolve() {
            if (!searchedLocally) {
                searchedLocally = true;
                repository.getLocalAccess().resolveComponentMetaData(dependencyMetaData.withRequestedVersion(version), identifier, result);
                if (result.hasResult() && result.isAuthoritative()) {
                    // Authoritative result means don't do remote search
                    searchedRemotely = true;
                }
            }
            if (result.getState() == Resolved || result.getState() == Failed) {
                return result;
            }
            if (!searchedRemotely) {
                searchedRemotely = true;
                repository.getRemoteAccess().resolveComponentMetaData(dependencyMetaData.withRequestedVersion(version), identifier, result);
            }
            return result;
        }

        public void resolve(DefaultBuildableModuleComponentMetaDataResolveResult target) {
            resolve();
            switch (result.getState()) {
                case Resolved:
                    target.resolved(result.getMetaData());
                    break;
                case Missing:
                    result.applyTo(target);
                    target.missing();
                    break;
                case Failed:
                    target.failed(result.getFailure());
                    break;
                case Unknown:
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private static class VersionListResult {
        private final DefaultBuildableModuleVersionListingResolveResult result = new DefaultBuildableModuleVersionListingResolveResult();
        private final ModuleComponentRepository repository;
        private final DependencyMetaData dependency;

        private boolean searchedLocally;
        private boolean searchedRemotely;

        public VersionListResult(DependencyMetaData dependency, ModuleComponentRepository repository) {
            this.dependency = dependency;
            this.repository = repository;
        }

        void resolve() {
            if (!searchedLocally) {
                searchedLocally = true;
                process(dependency, repository.getLocalAccess());
                if (result.hasResult()) {
                    if (result.isAuthoritative()) {
                        // Authoritative result - don't need to try remote
                        searchedRemotely = true;
                    }
                    return;
                }
                // Otherwise, try remotely
            }
            if (!searchedRemotely) {
                searchedRemotely = true;
                process(dependency, repository.getRemoteAccess());
            }

            // Otherwise, just reuse previous result
        }

        public boolean canMakeFurtherAttempts() {
            return !searchedRemotely;
        }

        public void applyTo(ResourceAwareResolveResult target) {
            result.applyTo(target);
        }

        private void process(DependencyMetaData dynamicVersionDependency, ModuleComponentRepositoryAccess moduleAccess) {
            moduleAccess.listModuleVersions(dynamicVersionDependency, result);
        }
    }
}
