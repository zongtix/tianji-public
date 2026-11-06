@{
    SshHost = 'developer@tianji-dev.example.invalid'
    SshKey = '%USERPROFILE%/.ssh/id_ed25519'
    RuntimeDirectory = 'target/local-environment'
    # Already maintained by the original boot-time, auto-reconnecting business tunnels.
    CorePorts = @(3306, 6379, 8848, 9848, 9849)
    BusinessPorts = @(5672, 8880, 8099)
    # Separate listeners prevent one optional tunnel failure from breaking business tunnels.
    Tunnels = @(
        @{ Name = 'elasticsearch'; LocalPort = 19201; RemotePort = 19201 }
        @{ Name = 'kibana'; LocalPort = 5601; RemotePort = 5601 }
        @{ Name = 'vector-redis'; LocalPort = 36379; RemotePort = 36379; When = 'redis' }
        @{ Name = 'chat-mongodb'; LocalPort = 27017; RemotePort = 27017; When = 'mongodb' }
    )
}
