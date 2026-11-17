package io.github.klaudystyle.dreambackup;

import io.github.klaudystyle.dreambackup.config.BackupSettings;
import io.github.klaudystyle.dreambackup.storage.ZipBackupStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_6_R3.CraftServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.logging.Level;

public final class DreamBackupPlugin extends JavaPlugin {
    private BackupSettings settings;
    private ZipBackupStore store;
    private BackupRun active;
    private CommandSender requester;
    private String lastOutcome = "No backups have run since the plugin started.";

    @Override
    public void onEnable() {
        if (!isSupportedServer()) {
            getLogger().severe("DreamBackup requires CraftBukkit for Minecraft 1.6.4 (v1_6_R3).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            saveDefaultConfig();
            YamlConfiguration defaults = new YamlConfiguration();
            try (InputStream resource = getResource("config.yml")) {
                defaults.load(resource);
            }
            YamlConfiguration config = new YamlConfiguration();
            config.load(new File(getDataFolder(), "config.yml"));
            config.setDefaults(defaults);
            settings = BackupSettings.load(config, Paths.get(""), getDataFolder().toPath());
            store = new ZipBackupStore(settings.getOutputDirectory(), settings.getRetention());
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            getLogger().log(Level.SEVERE, "Could not load DreamBackup configuration", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                collectOutcome();
            }
        }, 20L, 20L);

        long interval = settings.getIntervalTicks();
        if (interval > 0L) {
            getServer().getScheduler().runTaskTimer(this, new Runnable() {
                @Override
                public void run() {
                    startBackup(null);
                }
            }, interval, interval);
        }
    }

    private boolean isSupportedServer() {
        try {
            return CraftServer.class.isInstance(getServer());
        } catch (LinkageError incompatible) {
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (active != null) {
            active.stop();
            active = null;
        }
        requester = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && "now".equalsIgnoreCase(args[0]))) {
            startBackup(sender);
            return true;
        }
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            collectOutcome();
            sender.sendMessage(active == null ? lastOutcome : "A backup is running.");
            return true;
        }
        return false;
    }

    private void startBackup(CommandSender sender) {
        collectOutcome();
        if (active != null) {
            if (sender != null) {
                sender.sendMessage("A backup is already running.");
            }
            return;
        }
        if (sender != null) {
            sender.sendMessage("Backup started.");
        }

        WorldSnapshot snapshot = null;
        try {
            snapshot = WorldSnapshot.capture(getServer(), settings.getOutputDirectory());
            BackupRun run = new BackupRun(snapshot, settings.getAdditionalSources(), store, getLogger());
            run.start();
            requester = sender;
            active = run;
        } catch (IOException | RuntimeException failure) {
            if (snapshot != null) {
                try {
                    snapshot.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            lastOutcome = "Backup failed. See the server log for details.";
            getLogger().log(Level.SEVERE, "Could not take a world snapshot", failure);
            if (sender != null && !(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage(lastOutcome);
            }
        }
    }

    private void collectOutcome() {
        if (active == null || active.getOutcome() == null) {
            return;
        }
        lastOutcome = active.getOutcome();
        active = null;
        if (requester != null && !(requester instanceof ConsoleCommandSender)) {
            requester.sendMessage(lastOutcome);
        }
        requester = null;
    }
}
