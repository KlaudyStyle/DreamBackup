package io.github.klaudystyle.dreambackup;

import io.github.klaudystyle.dreambackup.storage.BackupSource;
import io.github.klaudystyle.dreambackup.storage.ZipBackupStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BackupRun implements Runnable {
    private final WorldSnapshot snapshot;
    private final List<BackupSource> sources;
    private final ZipBackupStore store;
    private final Logger logger;
    private final Thread worker;
    private volatile String outcome;

    BackupRun(WorldSnapshot snapshot, List<BackupSource> additionalSources,
              ZipBackupStore store, Logger logger) {
        this.snapshot = snapshot;
        this.sources = new ArrayList<BackupSource>(snapshot.getSources());
        this.sources.addAll(additionalSources);
        this.store = store;
        this.logger = logger;
        this.worker = new Thread(this, "DreamBackup archive");
        worker.setDaemon(true);
    }

    void start() {
        worker.start();
    }

    String getOutcome() {
        return outcome;
    }

    void stop() {
        worker.interrupt();
        try {
            worker.join(2000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        String result = "Backup failed. See the server log for details.";
        try {
            Path archive = store.create(sources);
            result = "Backup completed: " + archive.getFileName();
            logger.info("Backup completed: " + archive);
            try {
                store.prune();
            } catch (IOException | RuntimeException failure) {
                if (!worker.isInterrupted()) {
                    logger.log(Level.WARNING, "Could not remove old backup archives", failure);
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (worker.isInterrupted()) {
                result = "Backup was cancelled.";
            } else {
                logger.log(Level.SEVERE, "Could not create backup", failure);
            }
        } finally {
            try {
                snapshot.close();
            } catch (IOException | RuntimeException failure) {
                logger.log(Level.WARNING, "Could not remove temporary world files", failure);
            } finally {
                outcome = result;
            }
        }
    }
}
