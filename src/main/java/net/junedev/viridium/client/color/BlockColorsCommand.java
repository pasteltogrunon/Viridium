package net.junedev.viridium.client.color;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

public final class BlockColorsCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "blockcolors";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/blockcolors <rgb|oklab|contrast|axes|scale|size|spread|filter|refs|here|on|off|rescan|info|next|status|help>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (Minecraft.getMinecraft().theWorld == null) return;
        ColorCloud cloud = ColorCloud.INSTANCE;
        String command = args.length == 0 ? "oklab" : args[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "rgb":
            case "oklab":
            case "contrast":
                count(args, args.length == 0 ? 0 : 1);
                cloud.axes = command.equals("rgb") ? new ColorAxis[] { ColorAxis.R, ColorAxis.G, ColorAxis.B }
                    : command.equals("oklab") ? new ColorAxis[] { ColorAxis.A, ColorAxis.L, ColorAxis.LAB_B }
                        : new ColorAxis[] { ColorAxis.CHROMA, ColorAxis.L, ColorAxis.CONTRAST };
                cloud.show();
                break;
            case "axes":
                count(args, 4);
                try {
                    ColorAxis[] axes = { ColorAxis.parse(args[1]), ColorAxis.parse(args[2]), ColorAxis.parse(args[3]) };
                    cloud.axes = axes;
                } catch (IllegalArgumentException exception) {
                    throw new WrongUsageException("Axes: r g b l a lab_b chroma contrast");
                }
                cloud.show();
                break;
            case "scale":
            case "size":
            case "spread":
                count(args, 2);
                double value = parseDoubleBounded(
                    sender,
                    args[1],
                    command.equals("scale") ? 8 : command.equals("size") ? 0.1 : 0,
                    command.equals("scale") ? 192 : 4);
                if (!Double.isFinite(value)) throw new WrongUsageException("Expected a finite number");
                if (command.equals("scale")) cloud.scale = value;
                else if (command.equals("size")) cloud.size = value;
                else cloud.spread = value;
                cloud.rebuild();
                break;
            case "filter":
                if (args.length < 2) throw new WrongUsageException("/blockcolors filter <text|all>");
                cloud.filter = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                    .toLowerCase(Locale.ROOT);
                if (cloud.filter.equals("all")) cloud.filter = "";
                cloud.rebuild();
                break;
            case "refs":
                count(args, 2);
                if (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) {
                    throw new WrongUsageException("/blockcolors refs <on|off>");
                }
                cloud.references = args[1].equalsIgnoreCase("on");
                break;
            case "here":
                count(args, 1);
                cloud.here();
                cloud.show();
                break;
            case "on":
                count(args, 1);
                cloud.show();
                break;
            case "off":
                count(args, 1);
                cloud.hide();
                break;
            case "rescan":
                count(args, 1);
                cloud.rescan();
                cloud.show();
                break;
            case "next":
                count(args, 1);
                cloud.next();
                return;
            case "info":
                count(args, 1);
                sender.addChatMessage(new ChatComponentText(cloud.info()));
                return;
            case "status":
                count(args, 1);
                break;
            case "help":
                sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
                sender.addChatMessage(
                    new ChatComponentText("Presets: rgb (R,G,B), oklab (a,L,b), contrast (C,L,sigma L)."));
                sender.addChatMessage(
                    new ChatComponentText("Custom X Y Z: /blockcolors axes <r|g|b|l|a|lab_b|chroma|contrast> ... ..."));
                sender.addChatMessage(
                    new ChatComponentText(
                        "scale 8..192; size 0.1..4; spread 0..4 (0 = exact); filter <text|all>; refs on|off."));
                sender.addChatMessage(
                    new ChatComponentText(
                        "here: move cloud ahead; info: aimed sample; next: cycle overlaps; off: hide/cancel."));
                return;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
        sender.addChatMessage(new ChatComponentText(cloud.status()));
    }

    private static void count(String[] args, int expected) {
        if (args.length != expected) throw new WrongUsageException("/blockcolors help");
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(
            args,
            "rgb",
            "oklab",
            "contrast",
            "axes",
            "scale",
            "size",
            "spread",
            "filter",
            "refs",
            "here",
            "on",
            "off",
            "rescan",
            "info",
            "next",
            "status",
            "help");
        if (args[0].equalsIgnoreCase("axes") && args.length <= 4)
            return getListOfStringsMatchingLastWord(args, "r", "g", "b", "l", "a", "lab_b", "chroma", "contrast");
        if (args[0].equalsIgnoreCase("refs") && args.length == 2)
            return getListOfStringsMatchingLastWord(args, "on", "off");
        if (args[0].equalsIgnoreCase("filter") && args.length == 2)
            return getListOfStringsMatchingLastWord(args, "all", "minecraft:", "viridium:");
        return null;
    }
}
