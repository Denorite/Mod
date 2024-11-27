# Denorite Mod

A Fabric mod that connects Minecraft servers to TypeScript applications through WebSocket, enabling real-time event monitoring, custom command registration, and BlueMap integration.

## Features

- Real-time event broadcasting
- Dynamic command registration system
- BlueMap marker integration
- Secure WebSocket communication
- Automatic reconnection handling

## Requirements

- Minecraft 1.20.x
- Fabric Loader 0.15.x
- Fabric API 0.91.x+
- [Denorite Server](https://github.com/yourusername/denorite-server) running

## Installation

1. Install Fabric for your Minecraft server
2. Download the latest Denorite mod release
3. Place the mod in your server's `mods` folder
4. Create a configuration file at `config/denorite.json`:

```json
{
  "jwtToken": "your-jwt-token",
  "serverUrl": "ws://localhost:8082",
  "mcServerUrl": "localhost",
  "strictMode": false
}
```

## Custom Commands

Denorite allows you to register custom commands dynamically from your TypeScript application.

### Command Structure

```typescript
interface CommandData {
  name: string;
  subcommands?: SubCommand[];
  arguments?: CommandArgument[];
}

interface SubCommand {
  name: string;
  arguments?: CommandArgument[];
}

interface CommandArgument {
  name: string;
  type: "string" | "integer" | "player" | "item";
}
```

### Registering Commands

```typescript
// Simple command
{
  "name": "spawn",
  "arguments": [
    {
      "name": "player",
      "type": "player"
    }
  ]
}

// Command with subcommands
{
  "name": "team",
  "subcommands": [
    {
      "name": "add",
      "arguments": [
        {
          "name": "player",
          "type": "player"
        },
        {
          "name": "team",
          "type": "string"
        }
      ]
    },
    {
      "name": "remove",
      "arguments": [
        {
          "name": "player",
          "type": "player"
        }
      ]
    }
  ]
}
```

### BlueMap Integration

The mod provides comprehensive integration with BlueMap for dynamic marker management.

#### Marker Types

- POI Markers
- HTML Markers
- Line Markers
- Shape Markers
- Extrude Markers

#### Creating Markers

```typescript
// Create a marker set
{
  "subcommand": "createSet",
  "arguments": {
    "id": "spawn-points",
    "data": {
      "label": "Spawn Points",
      "toggleable": true,
      "defaultHidden": false
    }
  }
}

// Add a POI marker
{
  "subcommand": "add",
  "arguments": {
    "markerset": "spawn-points",
    "markerid": "main-spawn",
    "type": "poi",
    "data": {
      "label": "Main Spawn",
      "position": {
        "x": 100,
        "y": 64,
        "z": 100
      },
      "icon": "assets/spawn.svg",
      "maxDistance": 1000
    }
  }
}
```

## Event System

The mod broadcasts various events through WebSocket. Each event includes relevant data in JSON format. See the [Denorite Server documentation](https://github.com/yourusername/denorite-server) for a complete list of events.

## Security

The mod implements security through:

1. JWT-based authentication
2. Origin validation
3. Strict mode option for critical environments
4. Secure WebSocket communication

## Configuration Options

- `jwtToken`: Authentication token for WebSocket connection
- `serverUrl`: WebSocket server URL
- `mcServerUrl`: Minecraft server URL (for origin validation)
- `strictMode`: Whether to stop the server on connection loss

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.
