package claudechat;

import claudechat.BuiltFolder.Content;
import claudechat.BuiltFolder.Progress;
import claudechat.KnowledgeBase.CodePolicy;
import claudechat.KnowledgeBase.State;
import claudechat.KnowledgeBase.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStatusTest {
	private static Status status(CodePolicy policy, Content content, Progress progress) {
		return new Status(policy, content, progress, null);
	}

	@Test
	void excludedCodeIsHiddenBeforeItsFolderIsDeleted() {
		Status status = status(CodePolicy.EXCLUDED, Content.CODE, Progress.IDLE);

		assertFalse(status.hasCode());
		assertTrue(status.hasData());
		assertFalse(status.canDecompile());
		assertEquals(State.DATA_READY, status.state());
	}

	@Test
	void minecraftCanBeNeitherIncludedNorExcluded() {
		Status status = status(CodePolicy.ALWAYS, Content.NONE, Progress.QUEUED);

		assertFalse(status.includable());
		assertFalse(status.excludable());
		assertEquals(KnowledgeBase.Tier.CODE, status.tier());
		assertEquals(State.QUEUED, status.state());
	}

	@Test
	void aModWithoutCodeCanNeverBeDecompiled() {
		Status status = status(CodePolicy.NO_CODE, Content.DATA, Progress.IDLE);

		assertFalse(status.canDecompile());
		assertFalse(status.includable());
		assertFalse(status.excludable());
	}

	@Test
	void anOnDemandModCanGoEitherWay() {
		Status status = status(CodePolicy.ON_DEMAND, Content.DATA, Progress.IDLE);

		assertTrue(status.canDecompile());
		assertTrue(status.includable());
		assertTrue(status.excludable());
		assertEquals(KnowledgeBase.Tier.DATA, status.tier());
	}

	@Test
	void aFailedCodeBuildKeepsItsData() {
		Status status = status(CodePolicy.INCLUDED, Content.DATA, Progress.FAILED);

		assertTrue(status.hasData());
		assertTrue(status.canDecompile());
		assertEquals(State.FAILED, status.state());
	}
}
