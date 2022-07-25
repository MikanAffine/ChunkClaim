package gl.ky.cclaim

import gl.ky.cclaim.CChunk.Companion.cchunk
import gl.ky.cclaim.CChunk.Companion.chunkX
import gl.ky.cclaim.CChunk.Companion.chunkZ
import gl.ky.cclaim.CUser.Companion.cuser
import gl.ky.cclaim.Perm.Companion.DENY
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bukkit.*
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*

class Main : JavaPlugin() {
    override fun onEnable() {
        server.pluginManager.registerEvents(ChunkListener, this)
        server.getPluginCommand("chunkclaim")!!.setExecutor(this)
        server.getPluginCommand("chunkclaim")!!.tabCompleter = this
        loadData()
        logger.info("Plugin [ChunkClaim] has been enabled.")
    }

    override fun onDisable() {
        saveData()
        logger.info("Plugin [ChunkClaim] has been disabled.")
    }

    fun loadData() {
        if (!dataFolder.exists()) dataFolder.mkdirs()

        val f = File(dataFolder, "chunks.dat")
        if (!f.exists()) {
            f.createNewFile()
            CChunk.chunks = mutableSetOf()
        } else CChunk.chunks = Cbor.decodeFromByteArray(ListSerializer(CChunkSerializer), f.readBytes()).toMutableSet()

        val u = File(dataFolder, "users.dat")
        if (!u.exists()) {
            u.createNewFile()
            CUser.users = mutableMapOf()
        } else CUser.users =
            Cbor.decodeFromByteArray(ListSerializer(CUserSerializer), u.readBytes()).associateBy(CUser::uid)
                .toMutableMap()
    }

    fun saveData() {
        if (!dataFolder.exists()) dataFolder.mkdirs()

        val u = File(dataFolder, "chunks.dat")
        if (!u.exists()) u.createNewFile()
        u.writeBytes(Cbor.encodeToByteArray(SetSerializer(CChunkSerializer), CChunk.chunks))

        val f = File(dataFolder, "users.dat")
        if (!f.exists()) f.createNewFile()
        f.writeBytes(Cbor.encodeToByteArray(SetSerializer(CUserSerializer), CUser.users.values.toSet()))
    }

    fun CommandSender.sendHelp() {
        sendMessage("/ccl claim - 占领脚下的区块")
        sendMessage("/ccl unclaim - 解除占领脚下的区块")
        sendMessage("/ccl whose - 查询脚下的区块的归属者")
        sendMessage("/ccl friend <ID> - 让某人获得你的区块的权限")
        sendMessage("/ccl unfriend <ID> - 取消某人在你的区块的权限")
        sendMessage("/ccl build <true/false> - 设置陌生人能否在你的区块破坏/建造")
        sendMessage("/ccl interact <true/false> - 设置陌生人能否在你的区块与方块互动")
        sendMessage("/ccl entity <true/false> - 设置陌生人能否在你的区块与实体互动")
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<out String>,
    ): MutableList<String>? {
        if (sender !is Player) return null
        return when (args.size) {
            1 -> mutableListOf("claim", "unclaim", "whose", "friend", "unfriend", "build", "interact", "entity")
            2 -> when (args[0].lowercase()) {
                "friend" -> Bukkit.getOnlinePlayers().map(Player::getName).toMutableList()
                "unfriend" -> Bukkit.getOnlinePlayers().map(Player::getName).toMutableList()
                "build" -> mutableListOf("true", "false")
                "interact" -> mutableListOf("true", "false")
                "entity" -> mutableListOf("true", "false")
                else -> null
            }
            else -> null
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        sender as? Player ?: run {
            sender.sendMessage("该命令只能由玩家执行..")
            return@onCommand true
        }
        val u = sender.cuser
        if (args.isEmpty()) {
            sender.sendHelp()
            return true
        }
        when (args[0].lowercase()) {
            "all" -> {
                CChunk.chunks.map(CChunk::toString).forEach(sender::sendMessage)
                return true
            }

            "claim" -> {
                val l = sender.location
                return if (CChunk.claim(u, l.chunkX, l.chunkZ, sender.world.uid)) {
                    sender.sendMessage("占领成功!")
                    true
                } else {
                    sender.sendMessage("这个区块已经被占领!")
                    true
                }
            }

            "unclaim" -> {
                val c = sender.location.cchunk ?: run {
                    sender.sendMessage("这个区块未被占领!")
                    return@onCommand true
                }
                if (!sender.isOp && c.ownerUid != u.uid) {
                    sender.sendMessage("这个区块不是你的!")
                    return true
                }
                CChunk.unclaim(c)
                sender.sendMessage("解除占领成功!")
                return true
            }

            "whose" -> {
                val c = sender.location.cchunk ?: run {
                    sender.sendMessage("这个区块未被占领!")
                    return@onCommand true
                }
                sender.sendMessage("这个区块是 ${Bukkit.getOfflinePlayer(c.ownerUid).name} 的!")
                return true
            }
        }
        if (args.size < 2) {
            sender.sendMessage("参数不足!")
            return true
        }
        when (args[0].lowercase()) {
            "friend" -> {
                val p = (Bukkit.getPlayerExact(args[1]) ?: run {
                    sender.sendMessage("玩家 ${args[1]} 不在线!")
                    return@onCommand true
                }).cuser
                u.friend(p)
                sender.sendMessage("你已经将 ${args[1]} 设置为你的好友!")
                return true
            }

            "unfriend" -> {
                val p = (Bukkit.getPlayerExact(args[1]) ?: run {
                    sender.sendMessage("玩家 ${args[1]} 不在线!")
                    return@onCommand true
                }).cuser
                u.unfriend(p)
                sender.sendMessage("你已经将 ${args[1]} 移出你的好友!")
                return true
            }

            "build" -> {
                when (args[1].lowercase()) {
                    "true" -> u.otherPerm.build = true
                    "false" -> u.otherPerm.build = false

                    else -> {
                        sender.sendMessage("参数错误!")
                        return true
                    }
                }
                sender.sendMessage("你已经设置了陌生人的建造权限为 ${args[1]}!")
                return true
            }

            "interact" -> {
                when (args[1].lowercase()) {
                    "true" -> u.otherPerm.interact = true
                    "false" -> u.otherPerm.interact = false

                    else -> {
                        sender.sendMessage("参数错误!")
                        return true
                    }
                }
                sender.sendMessage("你已经设置了陌生人的方块互动权限为 ${args[1]}!")
                return true
            }

            "entity" -> {
                when (args[1].lowercase()) {
                    "true" -> u.otherPerm.entity = true
                    "false" -> u.otherPerm.entity = false

                    else -> {
                        sender.sendMessage("参数错误!")
                        return true
                    }
                }
                sender.sendMessage("你已经设置了陌生人的实体互动权限为 ${args[1]}!")
                return true
            }
        }
        return true
    }
}

@Serializable
data class Perm(
    var build: Boolean,
    var interact: Boolean,
    var entity: Boolean,
) {
    companion object {
        val DENY = Perm(false, false, false)
        val ALLOW = Perm(true, true, true)
    }
}

object ChunkListener : Listener {

    private fun calculatePerm(u: CUser, c: CChunk?) = if (c == null) Perm.ALLOW else calculatePerm(u, c.owner)
    private fun calculatePerm(u: CUser, o: CUser) = if (u in o.friends) Perm.ALLOW else o.otherPerm

    @EventHandler
    fun on(e: BlockPlaceEvent) {
        if (e.player.isOp) return
        val c = e.block.location.cchunk ?: return
        if (!calculatePerm(e.player.cuser, c).build) {
            e.isCancelled = true
            e.player.sendMessage("你没有权限在这个区块上建筑")
        }
    }

    @EventHandler
    fun on(e: BlockBreakEvent) {
        if (e.player.isOp) return
        val c = e.block.location.cchunk ?: return
        if (!calculatePerm(e.player.cuser, c).build) {
            e.isCancelled = true
            e.player.sendMessage("你没有权限在这个区块上建筑")
        }
    }

    @EventHandler(ignoreCancelled = false)
    fun on(e: PlayerInteractEvent) {
        if (e.player.isOp) return
        if (!e.hasBlock()) return
        if (!calculatePerm(e.player.cuser, e.clickedBlock!!.location.cchunk).interact) {
            e.isCancelled = true
            e.player.sendMessage("你没有权限在这个区块上与方块互动")
        }
    }

    @EventHandler
    fun on(e: EntityDamageByEntityEvent) {
        val u = (e.damager as? Player ?: return).cuser
        if (e.damager.isOp) return
        if (!calculatePerm(u, e.entity.location.cchunk).entity) {
            e.isCancelled = true
            e.damager.sendMessage("你没有权限在这个区块上与实体互动")
        }
    }

    @EventHandler
    fun on(e: PlayerInteractAtEntityEvent) {
        if (e.player.isOp) return
        if (!calculatePerm(e.player.cuser, e.rightClicked.location.cchunk).entity) {
            e.isCancelled = true
            e.player.sendMessage("你没有权限在这个区块上与实体互动")
        }
    }

}

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("java.util.UUID") {
        element("msb", Long.serializer().descriptor)
        element("lsb", Long.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeLong(value.mostSignificantBits)
        encoder.encodeLong(value.leastSignificantBits)
    }

    override fun deserialize(decoder: Decoder): UUID {
        val msb = decoder.decodeLong()
        val lsb = decoder.decodeLong()
        return UUID(msb, lsb)
    }
}

@Serializable(with = CChunkSerializer::class)
data class CChunk(
    val worldUid: UUID,
    val x: Int,
    val z: Int,
    val ownerUid: UUID,
) {
    val owner get() = CUser.getCUser(ownerUid)

    override fun hashCode() = ((x * 31) xor (z shl 4)) + (37 * ownerUid.hashCode())

    override fun equals(other: Any?): Boolean {
        if (other !is CChunk) return false
        return other.worldUid == worldUid && other.x == x && other.z == z
    }

    override fun toString() =
        "CChunk [$x, $z of ${Bukkit.getWorld(worldUid)?.name}, owner = ${Bukkit.getOfflinePlayer(ownerUid).name}]"

    companion object {
        var chunks = mutableSetOf<CChunk>()

        fun getCChunk(x: Int, z: Int, world: UUID): CChunk? {
            return chunks.find { it.x == x && it.z == z && it.worldUid == world }
        }

        fun claim(u: CUser, x: Int, z: Int, world: UUID): Boolean {
            if (getCChunk(x, z, world) != null) return false
            chunks.add(CChunk(world, x, z, u.uid))
            return true
        }

        fun unclaim(c: CChunk) {
            chunks.remove(c)
        }

        val Location.cchunk: CChunk? get() = getCChunk(chunkX, chunkZ, world!!.uid)
        val Location.chunkX get() = blockX / 16
        val Location.chunkZ get() = blockZ / 16
    }
}

object CChunkSerializer : KSerializer<CChunk> {
    override val descriptor = buildClassSerialDescriptor("gl.ky.cclaim.CChunk") {
        element("w", UUIDSerializer.descriptor)
        element("x", Int.serializer().descriptor)
        element("z", Int.serializer().descriptor)
        element("o", UUIDSerializer.descriptor)
    }

    override fun deserialize(decoder: Decoder): CChunk {
        val worldUid = (decoder.decodeSerializableValue(UUIDSerializer))
        val x = decoder.decodeInt()
        val z = decoder.decodeInt()
        val ownerUid = decoder.decodeSerializableValue(UUIDSerializer)
        return CChunk(worldUid, x, z, ownerUid)
    }

    override fun serialize(encoder: Encoder, value: CChunk) {
        encoder.encodeSerializableValue(UUIDSerializer, value.worldUid)
        encoder.encodeInt(value.x)
        encoder.encodeInt(value.z)
        encoder.encodeSerializableValue(UUIDSerializer, value.ownerUid)
    }

}

@Serializable(with = CUserSerializer::class)
data class CUser(
    var uid: UUID,
    var friends: MutableSet<CUser>,
    var otherPerm: Perm,
) {
    val chunks get() = CChunk.chunks.filter { it.ownerUid == uid }

    fun friend(u: CUser) {
        friends.add(u)
    }

    fun unfriend(u: CUser) {
        friends.remove(u)
    }

    companion object {
        var users = mutableMapOf<UUID, CUser>()

        val OfflinePlayer.cuser get() = getCUser(uniqueId)

        fun getCUser(uid: UUID): CUser = users.getOrPut(uid) {
            CUser(uid, mutableSetOf(), DENY)
        }
    }
}

object CUserSerializer : KSerializer<CUser> {
    override val descriptor = buildClassSerialDescriptor("gl.ky.cclaim.CUser") {
        element("uid", UUIDSerializer.descriptor)
        element("friends", SetSerializer(UUIDSerializer).descriptor)
        element("otherPerm", Perm.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): CUser {
        val uid = decoder.decodeSerializableValue(UUIDSerializer)
        val friends = decoder.decodeSerializableValue(SetSerializer(UUIDSerializer)).map(CUser::getCUser).toMutableSet()
        val otherPerm = decoder.decodeSerializableValue(Perm.serializer())
        return CUser(uid, friends, otherPerm)
    }

    override fun serialize(encoder: Encoder, value: CUser) {
        encoder.encodeSerializableValue(UUIDSerializer, value.uid)
        encoder.encodeSerializableValue(SetSerializer(UUIDSerializer), value.friends.map(CUser::uid).toSet())
        encoder.encodeSerializableValue(Perm.serializer(), value.otherPerm)
    }
}
