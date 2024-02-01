package org.catools.athena.rest.feign.git.model;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.catools.athena.core.model.MetadataDto;
import org.catools.athena.core.model.UserAliasDto;
import org.catools.athena.core.model.UserDto;
import org.catools.athena.git.model.CommitDto;
import org.catools.athena.git.model.DiffEntryDto;
import org.catools.athena.git.model.GitRepositoryDto;
import org.catools.athena.git.model.TagDto;
import org.catools.athena.rest.feign.git.helpers.AthenaGitApi;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Data
@RequiredArgsConstructor
@Accessors(chain = true)
@Slf4j
public class RepositoryInfo {
  @Getter(AccessLevel.NONE)
  private final Git git;
  private final String name;
  private final String url;
  private final boolean persistRecords;

  private GitRepositoryDto repositoryDto = new GitRepositoryDto();
  private Set<UserDto> users = new HashSet<>();
  private Set<TagDto> tags = new HashSet<>();
  private Set<MetadataDto> metadata = new HashSet<>();
  private Set<CommitDto> commits = new HashSet<>();

  public void readRepository(int totalThreads, long timeout, TimeUnit unit) throws GitAPIException, IOException, InterruptedException {
    readRepository(totalThreads, timeout, unit, null);
  }

  public void readRepository(Date since, Date until, int totalThreads, long timeout, TimeUnit unit) throws GitAPIException, IOException, InterruptedException {
    readRepository(totalThreads, timeout, unit, CommitTimeRevFilter.between(since, until));
  }

  public void readRepository(int totalThreads, long timeout, TimeUnit unit, RevFilter filter) throws GitAPIException, IOException, InterruptedException {
    setRepository();
    readCommits(filter, totalThreads, timeout, unit);
  }

  protected void setRepository() {
    repositoryDto = new GitRepositoryDto();
    repositoryDto.setUrl(url);
    repositoryDto.setName(name);
    repositoryDto.setLastSync(Instant.now());
    if (persistRecords)
      AthenaGitApi.persistRepository(repositoryDto);
  }

  protected void readCommits(RevFilter filter, int totalThreads, long timeout, TimeUnit unit) throws IOException, GitAPIException, InterruptedException {
    Iterator<RevCommit> commits = git.log().setRevFilter(filter).all().call().iterator();

    Repository repo = git.getRepository();
    ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
    executor.execute(() -> {
      while (commits.hasNext()) {
        try {
          readCommit(repo, commits.next());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
    executor.shutdown();
    executor.awaitTermination(timeout, unit);
  }

  protected void readCommit(Repository repo, RevCommit commit) throws IOException, GitAPIException {
    CommitDto gitCommit = new CommitDto();
    gitCommit.setHash(commit.getName());

    if (commit.getParentCount() > 0)
      gitCommit.setParentHash(commit.getParent(0).getName());

    gitCommit.setParentCount(commit.getParentCount());
    gitCommit.setCommitTime(commit.getAuthorIdent().getWhen().toInstant());
    gitCommit.setShortMessage(commit.getShortMessage());

    gitCommit.setAuthor(readPerson(commit.getAuthorIdent()));
    gitCommit.setCommitter(readPerson(commit.getCommitterIdent()));

    readDiffEntries(repo, commit, gitCommit);
    readRelatedTags(commit, gitCommit);
    readMetadata(repo, commit, gitCommit);

    gitCommit.setInserted(gitCommit.getDiffEntries().stream().map(DiffEntryDto::getInserted).reduce(Integer::sum).orElse(0));
    gitCommit.setDeleted(gitCommit.getDiffEntries().stream().map(DiffEntryDto::getDeleted).reduce(Integer::sum).orElse(0));

    if (persistRecords)
      AthenaGitApi.persistCommit(this, gitCommit);

    commits.add(gitCommit);

    log.info("{} process finished, diffs: {} [+{},-{}], author: {}, committer: {}, tags: {}, metadata: {}. [total commits: {}]",
        gitCommit.getHash(),
        gitCommit.getDiffEntries().size(),
        gitCommit.getInserted(),
        gitCommit.getDeleted(),
        gitCommit.getAuthor(),
        gitCommit.getCommitter(),
        gitCommit.getTags().size(),
        gitCommit.getMetadata().size(),
        commits.size());
  }

  protected Set<MetadataDto> readMetadata(Repository repo, RevCommit commit, CommitDto gitCommit) {
    return new HashSet<>();
  }

  protected void readRelatedTags(RevCommit commit, CommitDto gitCommit) throws IOException, GitAPIException {
    List<Ref> list = git.tagList().setContains(commit.getId()).call();

    gitCommit.getTags().clear();
    for (Ref rTag : list) {
      TagDto tag = new TagDto()
          .setName(rTag.getName());

      ObjectId tagObjectId = rTag.getObjectId();
      if (tagObjectId != null)
        tag.setHash(tagObjectId.getName());

      if (persistRecords)
        AthenaGitApi.persistTag(tag);

      gitCommit.getTags().add(tag);
    }

    tags.addAll(gitCommit.getTags());
  }

  protected String readPerson(PersonIdent person) {
    UserDto user = new UserDto();

    if (!StringUtils.isEmptyOrNull(person.getName())) {
      user.setUsername(person.getName().toLowerCase());

      if (!StringUtils.isEmptyOrNull(person.getEmailAddress()))
        user.getAliases().add(new UserAliasDto().setAlias(person.getEmailAddress().toLowerCase()));

    } else if (!StringUtils.isEmptyOrNull(person.getEmailAddress()))
      user.setUsername(person.getEmailAddress().toLowerCase());

    if (persistRecords)
      AthenaGitApi.persistUser(user);

    users.add(user);
    return user.getUsername();
  }

  protected void readDiffEntries(Repository repo, RevCommit commit, CommitDto gitCommit) throws IOException {
    gitCommit.getDiffEntries().clear();

    if (commit.getParentCount() == 0) {
      readCommitDiff(repo, commit, null, gitCommit.getDiffEntries());
    } else {
      readCommitDiff(repo, commit, commit.getParent(0), gitCommit.getDiffEntries());
    }
  }

  protected void readCommitDiff(Repository repo, RevCommit commit, RevCommit parent, Set<DiffEntryDto> diffEntries) throws IOException {
    AbstractTreeIterator parentTree = getParser(repo, parent);
    AbstractTreeIterator commitTree = getParser(repo, commit);

    DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);
    diffFormatter.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
    diffFormatter.setRepository(repo);
    diffFormatter.setDetectRenames(true);

    List<DiffEntry> entries = diffFormatter.scan(parentTree, commitTree);

    for (DiffEntry entry : entries) {
      readDiffEntry(entry, diffFormatter, diffEntries);
    }
  }

  protected void readDiffEntry(DiffEntry entry, DiffFormatter diffFormatter, Set<DiffEntryDto> diffEntries) throws IOException {
    DiffEntryDto gitFileChange = new DiffEntryDto();
    gitFileChange.setOldPath(entry.getOldPath());
    gitFileChange.setNewPath(entry.getNewPath());
    gitFileChange.setChangeType(entry.getChangeType().name());
    gitFileChange.setInserted(0);
    gitFileChange.setDeleted(0);

    EditList edits = diffFormatter.toFileHeader(entry).toEditList();

    for (Edit edit : edits) {
      gitFileChange.setDeleted(gitFileChange.getDeleted() + edit.getLengthA());
      gitFileChange.setInserted(gitFileChange.getInserted() + edit.getLengthB());
    }

    diffEntries.add(gitFileChange);
  }

  protected static String getContentDiff(Repository repository, DiffEntry diff) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         DiffFormatter formatter = new DiffFormatter(out)) {

      formatter.setRepository(repository);
      formatter.format(diff);

      return out.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static AbstractTreeIterator getParser(Repository repo, RevCommit commi) throws IOException {
    if (commi == null)
      return new EmptyTreeIterator();

    CanonicalTreeParser parentTree = new CanonicalTreeParser();
    parentTree.reset(repo.newObjectReader(), commi.getTree());
    return parentTree;
  }
}
