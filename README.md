# GameCoordinator

AfterBloom MC's solution to managing Minigames. The coordinator handles:

* Managing which games are active
* Player MatchMaking
* Provisioning Backup servers
* long running games (roblox tycoon styled)
* Custom Events triggered by admins
* and more to come soon!

The system uses redis pub/sub to communicate with gameservers through a JSON API. Documentation on this API will be made once a release is available.

Note: If you are not an AfterBloom dev Please don't open issues here! Use the bug reports system in discord (.gg/afterbloom)

(and no, that webhook in commit history will not work)
